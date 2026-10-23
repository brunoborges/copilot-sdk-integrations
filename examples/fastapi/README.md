# FastAPI workshop agents

Two one-shot applications use `copilot-fastapi` agent definitions and its FastAPI
lifespan to own the runtime. No server is exposed: the CLI obtains an agent using
the integration's `Copilot.get()` API for background tasks. `app.py` also exposes
the usual health router for anyone embedding the application.

From the **repository root**, install the unreleased packages locally:

```sh
uv venv --python 3.12 examples/.venv
uv pip install --python examples/.venv/bin/python \
  -e python/core -e python/fastapi -e examples/python-common -e examples/fastapi
examples/.venv/bin/python -m workshop_fastapi museum --facts apollo11
examples/.venv/bin/python -m workshop_fastapi museum --facts reef --research --html exhibit.html
examples/.venv/bin/python -m workshop_fastapi accessibility --url https://example.com
```

See [the Python agents guide](../python-common/README.md) for runtime and browser
setup, custom facts, contracts, safety boundaries, and offline tests.
