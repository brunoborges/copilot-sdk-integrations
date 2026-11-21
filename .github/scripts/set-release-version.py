#!/usr/bin/env python3
"""Stamp a release version into the build files of each ecosystem.

Usage: set-release-version.py VERSION [java] [node] [python]

VERSION is the tag without the leading "v": X.Y.Z, optionally followed by
-alpha.N, -beta.N, -rc.N or -dev.N. Python versions are converted to PEP 440.
.NET packages take the version at pack time (-p:Version=VERSION), so they
need no stamping. The release workflow runs this on a throwaway checkout;
the stamped files are never committed.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+(-(alpha|beta|rc|dev)\.\d+)?$")
PEP440_SUFFIXES = {"alpha": "a", "beta": "b", "rc": "rc", "dev": ".dev"}
NODE_PACKAGES = ["core", "ai", "nestjs", "fastify"]
PYTHON_PACKAGES = ["core", "django", "fastapi"]


def stamp_java(version: str) -> None:
    core_pom = (ROOT / "java/core/pom.xml").read_text()
    current = re.search(r"<artifactId>copilot-core</artifactId>\s*<version>([^<]+)</version>", core_pom).group(1)
    poms = sorted((ROOT / "java").rglob("pom.xml")) + [ROOT / "testcontainers/pom.xml"]
    poms = [pom for pom in poms if "target" not in pom.parts]
    for pom in poms:
        text = pom.read_text()
        old = f"<version>{current}</version>"
        if old not in text:
            sys.exit(f"{pom.relative_to(ROOT)} does not use the shared version {current}")
        pom.write_text(text.replace(old, f"<version>{version}</version>"))
    print(f"java: {current} -> {version} in {len(poms)} POMs")


def stamp_node(version: str) -> None:
    internal = set()
    manifests = [ROOT / "nodejs/packages" / name / "package.json" for name in NODE_PACKAGES]
    for manifest in manifests:
        internal.add(json.loads(manifest.read_text())["name"])
    workspaces = json.loads((ROOT / "nodejs/package.json").read_text())["workspaces"]
    for workspace in workspaces:
        manifest = ROOT / "nodejs" / workspace / "package.json"
        data = json.loads(manifest.read_text())
        if data["name"] in internal:
            data["version"] = version
        for field in ("dependencies", "peerDependencies", "devDependencies"):
            for name in data.get(field, {}):
                if name in internal:
                    data[field][name] = version
        manifest.write_text(json.dumps(data, indent=2) + "\n")
    print(f"node: {', '.join(sorted(internal))} -> {version}")


def pep440(version: str) -> str:
    base, _, pre = version.partition("-")
    if not pre:
        return base
    label, number = pre.split(".")
    return f"{base}{PEP440_SUFFIXES[label]}{number}"


def stamp_python(version: str) -> None:
    py_version = pep440(version)
    for name in PYTHON_PACKAGES:
        pyproject = ROOT / "python" / name / "pyproject.toml"
        text = pyproject.read_text()
        text, count = re.subn(r'(?m)^version = "[^"]+"$', f'version = "{py_version}"', text)
        if count != 1:
            sys.exit(f"{pyproject.relative_to(ROOT)}: expected one project version")
        text = re.sub(r'"copilot-agent-core==[^"]+"', f'"copilot-agent-core=={py_version}"', text)
        pyproject.write_text(text)
    print(f"python: {', '.join(PYTHON_PACKAGES)} -> {py_version}")


STAMPERS = {"java": stamp_java, "node": stamp_node, "python": stamp_python}


def main(argv: list[str]) -> None:
    if not argv or not VERSION_PATTERN.match(argv[0]):
        sys.exit(__doc__)
    version, ecosystems = argv[0], argv[1:] or list(STAMPERS)
    for ecosystem in ecosystems:
        if ecosystem not in STAMPERS:
            sys.exit(f"unknown ecosystem: {ecosystem}")
        STAMPERS[ecosystem](version)


if __name__ == "__main__":
    main(sys.argv[1:])
