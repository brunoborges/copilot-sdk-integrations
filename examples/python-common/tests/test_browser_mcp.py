import asyncio
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from copilot_agents.tools import CopilotTool
from workshop_examples.agents import Run, accessibility_options
from workshop_examples.domain import ApprovedFacts


@pytest.mark.skipif(os.environ.get("WORKSHOP_MCP_TESTS") != "1", reason="Requires Node.js and the pinned MCP browser installation.")
def test_real_mcp_produces_snapshot_readable_by_the_application(tmp_path):
    class Page(BaseHTTPRequestHandler):
        def do_GET(self):
            body = b'<!doctype html><html lang="en"><title>Workshop fixture</title><main><h1>Workshop fixture</h1><button>Explore</button></main></html>'
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *arguments):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Page)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path, target=f"http://127.0.0.1:{server.server_port}/")
    configuration = accessibility_options(run, CopilotTool)["mcp_servers"]["playwright"]

    async def smoke():
        process = await asyncio.create_subprocess_exec(
            configuration["command"], *configuration["args"], cwd=configuration["working_directory"],
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        async def request(number, method, params):
            message = {"jsonrpc": "2.0", "id": number, "method": method, "params": params}
            process.stdin.write((json.dumps(message) + "\n").encode())
            await process.stdin.drain()
            while line := await asyncio.wait_for(process.stdout.readline(), 120):
                response = json.loads(line)
                if response.get("id") == number:
                    assert "error" not in response, response
                    return response["result"]
            raise RuntimeError((await process.stderr.read()).decode())

        try:
            await request(1, "initialize", {
                "protocolVersion": "2024-11-05", "capabilities": {},
                "clientInfo": {"name": "workshop-smoke", "version": "1.0"},
            })
            process.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
            await process.stdin.drain()
            result = await request(2, "tools/call", {
                "name": "browser_navigate", "arguments": {"url": run.target},
            })
            assert not result.get("isError"), result
            snapshot = run.read_latest_accessibility_snapshot()
            assert 'heading "Workshop fixture"' in snapshot
            assert 'button "Explore"' in snapshot
        finally:
            process.stdin.close()
            try:
                await asyncio.wait_for(process.wait(), 10)
            except TimeoutError:
                process.terminate()
                await process.wait()

    try:
        asyncio.run(smoke())
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
