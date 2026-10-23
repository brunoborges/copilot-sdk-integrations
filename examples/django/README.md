# Django workshop agents

A native Django `workshop` management command runs the museum and accessibility
agents through `copilot-django`. Agents and tools are registered with the Django
integration; settings configure the lazy process runtime, which the command shuts
down even on failure. No HTTP server, migrations, or database setup is required.

From the **repository root**:

```sh
uv venv --python 3.12 examples/.venv
uv pip install --python examples/.venv/bin/python \
  -e python/core -e python/django -e examples/python-common -e examples/django
examples/.venv/bin/python examples/django/manage.py workshop museum --facts apollo11
examples/.venv/bin/python examples/django/manage.py workshop museum --facts terracotta --research --html exhibit.html
examples/.venv/bin/python examples/django/manage.py workshop accessibility --url https://example.com
```

See [the Python agents guide](../python-common/README.md) for runtime and browser
setup, custom facts, contracts, safety boundaries, and offline tests.
