import pytest
from copilot import RuntimeConnection

from copilot_agents import connection
from copilot_agents.connection import build_client, client_mode, parse_runtime_url, runtime_connection


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("4321", ("localhost", 4321)),
        ("runtime:4321", ("runtime", 4321)),
        (":4321", ("localhost", 4321)),
        ("http://runtime.internal:8080", ("runtime.internal", 8080)),
        ("https://10.0.0.5:443/", ("10.0.0.5", 443)),
        ("[::1]:4321", ("::1", 4321)),
    ],
)
def test_parse_runtime_url(url, expected):
    assert parse_runtime_url(url) == expected


@pytest.mark.parametrize("url", ["runtime", "runtime:0", "runtime:70000", "runtime:abc", "a:b:1", "[nope]:1"])
def test_parse_runtime_url_rejects_invalid(url):
    with pytest.raises(ValueError):
        parse_runtime_url(url)


def test_uri_attaches_to_host_and_port():
    connection = runtime_connection("uri", url="http://runtime:4321", connection_token="secret")
    assert connection == RuntimeConnection.for_tcp("runtime", 4321, connection_token="secret")


def test_owned_connections_spawn_or_host_the_runtime():
    assert runtime_connection("tcp", port=9000, cli_path="/bin/copilot", base_directory="/b") == (
        RuntimeConnection.for_spawned_tcp(port=9000, path="/bin/copilot", base_directory="/b")
    )
    assert runtime_connection("stdio", cli_path="/bin/copilot", connection_token="") == (
        RuntimeConnection.for_spawned_stdio(path="/bin/copilot")
    )
    assert runtime_connection("inprocess", base_directory="/b") == RuntimeConnection.for_inprocess(base_directory="/b")
    with pytest.raises(ValueError):
        runtime_connection("pipe")


def test_client_mode_accepts_legacy_name():
    assert client_mode("copilot-cli") == "coding-agent"
    assert client_mode("empty") == "empty"
    with pytest.raises(ValueError):
        client_mode("chat")


def test_uri_client_omits_runtime_startup_settings():
    # The SDK rejects token, log level, and telemetry for runtimes the client does not own.
    build_client("uri", mode="copilot-cli", url="runtime:4321", log_level="debug", github_token="ghu_x")


def test_runtime_download_pending(monkeypatch):
    monkeypatch.delenv("COPILOT_CLI_PATH", raising=False)
    monkeypatch.setattr(connection, "runtime_downloaded", lambda: False)
    assert connection.runtime_download_pending("stdio")
    assert not connection.runtime_download_pending("tcp", "/bin/copilot")
    assert connection.runtime_download_pending("inprocess", "/bin/copilot")
    assert not connection.runtime_download_pending("uri")
    monkeypatch.setenv("COPILOT_CLI_PATH", "/bin/copilot")
    assert not connection.runtime_download_pending("inprocess")
