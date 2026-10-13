#!/usr/bin/env python3
"""Pin, verify and prepare Nexus without editing the upstream checkout."""
import argparse
import datetime
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "upstream/nexus-public"
LOCK = ROOT / "upstream.lock.json"
REPOSITORY = "https://github.com/sonatype/nexus-public.git"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SECURITY = Path("public/common/components/security/nexus-security")


def git(*args, cwd=SOURCE):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def release_key(tag):
    match = re.fullmatch(r"release-(\d+)\.(\d+)\.(\d+)-(\d+)", tag)
    if not match:
        raise ValueError(f"Неверный тег релиза: {tag}")
    return tuple(map(int, match.groups()))


def releases(remote_output):
    result = {}
    for line in remote_output.splitlines():
        sha, ref = line.split()
        if ref.endswith("^{}"):
            result[ref.removeprefix("refs/tags/").removesuffix("^{}")] = sha
    return {tag: sha for tag, sha in result.items()
            if re.fullmatch(r"release-3\.\d+\.\d+-\d+", tag)}


def verify():
    lock = json.loads(LOCK.read_text())
    if lock["repository"] != REPOSITORY or git("remote", "get-url", "origin") != REPOSITORY:
        raise ValueError("Неожиданный upstream URL")
    if git("rev-parse", "HEAD") != lock["commit"]:
        raise ValueError("Upstream HEAD не соответствует lock-файлу")
    # A fresh submodule clone may fetch the pinned commit without its annotated tag.
    # HEAD and POM are always checked; validate tag metadata whenever it is present.
    tag_present = subprocess.run(["git", "show-ref", "--verify", "--quiet",
                                  f'refs/tags/{lock["tag"]}'], cwd=SOURCE).returncode == 0
    if tag_present and git("rev-parse", f'{lock["tag"]}^{{commit}}') != lock["commit"]:
        raise ValueError("Тег не соответствует закрепленному коммиту")
    if git("status", "--porcelain", "--untracked-files=all"):
        raise ValueError("В upstream есть локальные изменения; сохраните их отдельно")
    pom = ET.parse(SOURCE / "pom.xml").getroot()
    if pom.findtext("m:version", namespaces=NS) != lock["version"]:
        raise ValueError("Версия POM не соответствует lock-файлу")
    return lock


def prepare(with_extensions=True):
    lock = verify()
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    destination = build / "source"
    # Do not overwrite files that were not created by this tool.
    if destination.exists() and not (destination / ".nexus-build-copy").is_file():
        raise ValueError("build/source существует, но не является управляемой копией")
    with tempfile.TemporaryDirectory(prefix="prepare-", dir=build) as temporary:
        stage = Path(temporary) / "source"
        stage.mkdir()
        archive = Path(temporary) / "upstream.tar"
        with archive.open("wb") as output:
            subprocess.run(["git", "archive", lock["commit"]], cwd=SOURCE,
                           stdout=output, check=True)
        with tarfile.open(archive) as source_archive:
            source_archive.extractall(stage, filter="data")
        if with_extensions:
            apply_extensions(stage)
        frontend_lock = ROOT / "config" / ("frontend-" + lock["version"] + ".yarn.lock")
        frontend_provenance = {}
        if frontend_lock.is_file():
            shutil.copyfile(frontend_lock, stage / "yarn.lock")
            import hashlib
            frontend_provenance["frontend_lock_sha256"] = hashlib.sha256(frontend_lock.read_bytes()).hexdigest()
        (stage / ".nexus-build-copy").write_text(lock["commit"] + "\n")
        (stage / "BUILD-PROVENANCE.json").write_text(json.dumps({
            **lock, "extensions": with_extensions, **frontend_provenance,
        }, indent=2) + "\n")
        if destination.exists():
            shutil.rmtree(destination)
        shutil.move(str(stage), destination)
    print(f"Подготовлено {destination} ({lock['version']})")


def apply_extensions(stage):
    module = stage / SECURITY
    if not module.is_dir():
        raise ValueError("Изменился путь Nexus security; требуется адаптация расширений")
    extension = ROOT / "extensions/keycloak"
    for tree, target in [("src/main/java", "src/main/java"),
                         ("src/test/java", "src/test/java"),
                         ("adapter/src/main/java", "src/main/java")]:
        shutil.copytree(extension / tree, module / target, dirs_exist_ok=True)
    pom = module / "pom.xml"
    content = pom.read_text()
    marker = "  <dependencies>"
    if content.count(marker) != 1 or "nimbus-jose-jwt" in content:
        raise ValueError("Изменился security POM; автоматическая адаптация остановлена")
    addition = '''
    <!-- Nexus extensions: Keycloak JWT verification, version managed by upstream. -->
    <dependency>
      <groupId>com.nimbusds</groupId>
      <artifactId>nimbus-jose-jwt</artifactId>
    </dependency>
'''
    pom.write_text(content.replace(marker, marker + addition, 1))
    # The public OAuth UI is reused by our independently implemented open OIDC provider.
    # Only its route is adapted; upstream checkout and all other edition checks stay intact.
    frontend = stage / "public/common/components"
    heritage = frontend / "nexus-coreui-plugin/src/frontend/src/routerConfig/routes/adminRoutes.js"
    data = heritage.read_text()
    anchor = "name: ADMIN.SECURITY.OAUTH2.ROOT,"
    start = data.index(anchor)
    end = data.index("// === admin/support ===", start)
    block = data[start:end]
    if block.count("bundle: 'nexus-oauth2-plugin'") != 1 or block.count("editions: ['PRO']") != 1:
        raise ValueError("Изменился OAuth UI route; требуется адаптация open OIDC")
    block = block.replace("bundle: 'nexus-oauth2-plugin'", "bundle: 'nexus-open-oidc'")
    block = block.replace("editions: ['PRO']", "editions: ['CORE']")
    heritage.write_text(data[:start] + block + data[end:])
    preview = frontend / "nexus-ui-plugin/src/frontend/src/components/preview/pages/settings/settingsConfig.ts"
    data = preview.read_text()
    start = data.index("id: 'oauth2'")
    end = data.index("\n      {\n        id:", start)
    block = data[start:end]
    if block.count("bundle: 'nexus-oauth2-plugin'") != 1 or block.count("editions: ['PRO']") != 1:
        raise ValueError("Изменился Preview OAuth UI route; требуется адаптация open OIDC")
    block = block.replace("bundle: 'nexus-oauth2-plugin'", "bundle: 'nexus-open-oidc'").replace("editions: ['PRO']", "editions: ['CORE']")
    preview.write_text(data[:start] + block + data[end:])
    subprocess.run([sys.executable, str(ROOT / "build-support/oidc-ui-overlay.py"), str(stage)], check=True)
    subprocess.run([sys.executable, str(ROOT / "build-support/branding-overlay.py"), str(stage)], check=True)


def latest():
    found = releases(git("ls-remote", "--tags", REPOSITORY))
    if not found:
        raise ValueError("Нет опубликованных аннотированных релизов Nexus 3")
    tag = max(found, key=release_key)
    return tag, found[tag]


def update(tag):
    old = verify()
    if tag is None:
        tag, expected = latest()
    else:
        release_key(tag)
        refs = releases(git("ls-remote", "--tags", REPOSITORY, f"refs/tags/{tag}*"))
        if tag not in refs:
            raise ValueError("Релиз не опубликован в upstream")
        expected = refs[tag]
    git("fetch", "--depth", "1", "origin", "tag", tag)
    if git("rev-parse", f"{tag}^{{commit}}") != expected:
        raise ValueError("Коммит тега отличается от опубликованного")
    git("checkout", "--detach", tag)
    candidate = dict(old, tag=tag, version=tag.removeprefix("release-"), commit=expected,
                     checked_on=datetime.datetime.now(datetime.timezone.utc).date().isoformat())
    # Roll back the checkout if the release is incompatible with this pipeline.
    required = SOURCE / SECURITY / "pom.xml"
    pom = ET.parse(SOURCE / "pom.xml").getroot()
    rules = pom.findall(".//m:requireJavaVersion/m:version", NS)
    if (not required.is_file() or pom.findtext("m:version", namespaces=NS) != candidate["version"]
            or not rules or any(node.text != f'[{old["java_major"]},)' for node in rules)):
        git("checkout", "--detach", old["commit"])
        raise ValueError("Новый релиз требует ручной адаптации структуры или Java toolchain")
    LOCK.write_text(json.dumps(candidate, indent=2) + "\n")
    print(f"Upstream обновлен до {tag}. Запустите make verify и make build; затем сохраните оба изменения в Git.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("verify")
    sub.add_parser("latest")
    prep = sub.add_parser("prepare")
    prep.add_argument("--core-only", action="store_true")
    upd = sub.add_parser("update")
    upd.add_argument("--tag")
    args = parser.parse_args()
    try:
        if args.command == "verify":
            print(json.dumps(verify(), ensure_ascii=False, indent=2))
        elif args.command == "latest":
            print(*latest())
        elif args.command == "prepare":
            prepare(not args.core_only)
        else:
            update(args.tag)
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Ошибка: {error}\n")


if __name__ == "__main__":
    main()
