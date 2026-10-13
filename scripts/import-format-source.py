#!/usr/bin/env python3
"""Import an EPL source artifact into a native current-Nexus format module."""
import argparse
import re
import zipfile
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("format")
parser.add_argument("archive", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
module = root / "extensions/formats" / ("nexus-repository-" + args.format)
if (module / "pom.xml").exists():
    raise SystemExit("Refusing to overwrite an adapted module: " + str(module))
module.mkdir(parents=True, exist_ok=True)
prefix = {"npm": "Npm", "pypi": "PyPi"}.get(args.format, args.format.title())
retained = 0
with zipfile.ZipFile(args.archive) as archive:
    for name in archive.namelist():
        if not name.endswith(".java") or name.endswith("package-info.java"):
            continue
        text = archive.read(name).decode()
        if "Eclipse Public License Version 1.0" not in text:
            raise SystemExit("Unrecognized source license: " + name)
        if "/orient/" in name or "/upgrade/" in name or "/repair/" in name or "/tasks/" in name:
            continue
        text = text.replace("javax.inject.Inject", "org.springframework.beans.factory.annotation.Autowired")
        text = text.replace("javax.inject.Named", "org.springframework.beans.factory.annotation.Qualifier")
        text = text.replace("javax.inject.Provider", "jakarta.inject.Provider")
        text = text.replace("import javax.inject.Singleton;", "")
        text = text.replace("javax.servlet.", "jakarta.servlet.").replace("javax.ws.rs.", "jakarta.ws.rs.")
        text = text.replace("javax.validation.", "jakarta.validation.")
        text = text.replace("org.sonatype.goodies.common.ComponentSupport", "org.sonatype.nexus.extensions.compat.ComponentSupport")
        text = re.sub(r"(\$\{[^}\n]+):-(\d+)(})", r"\1:\2\3", text)
        text = text.replace("@Inject", "@Autowired").replace("@Singleton", "")
        text = re.sub(r"@Named\(([^\n]+)\)", r"@Qualifier(\1)", text)
        text = re.sub(r"@Named\b", "@org.springframework.stereotype.Component", text)
        # A qualified class is still a discoverable component; qualifiers on parameters are unchanged.
        text = re.sub(r"(@Qualifier\([^\n]+\)\s*)(public\s+(?:final\s+)?class\b)",
                      r"@org.springframework.stereotype.Component\n\1\2", text)
        text = text.replace("import org.sonatype.nexus.repository.content.store.FormatStoreModule;",
                            "import org.sonatype.nexus.repository.content.store.FormatStoreSupport;")
        if "extends FormatStoreModule<" in text:
            text = text.replace("extends FormatStoreModule<", "extends FormatStoreSupport<")
            store_prefix = re.search(r"(\w+)AssetBlobDAO>", text).group(1)
            text = re.sub(r"(" + store_prefix + r"AssetBlobDAO)>", r"\1, " + store_prefix + "BrowseNodeDAO>", text)
            package = re.search(r"package ([^;]+);", text).group(1)
            browse = f'''/* SPDX-License-Identifier: EPL-1.0 */
package {package};
import org.sonatype.nexus.repository.content.browse.store.BrowseNodeDAO;
public interface {store_prefix}BrowseNodeDAO extends BrowseNodeDAO {{}}
'''
            browse_path = module / "src/main/java" / package.replace(".", "/") / (store_prefix + "BrowseNodeDAO.java")
            browse_path.parent.mkdir(parents=True, exist_ok=True)
            browse_path.write_text(browse)
        if re.search(r"extends\s+(?:\w*FacetSupport|ContentFacetSupport|.*FacetImpl)\b", text):
            text = re.sub(r"(public\s+(?:final\s+)?class\b)",
                          r'@org.springframework.context.annotation.Scope("prototype")\n\1', text, count=1)
        destination = module / "src/main/java" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
        retained += 1
deps = ["nexus-open-platform", "nexus-plugin-api", "nexus-repository-content", "nexus-repository-services", "nexus-cleanup",
        "nexus-rapture", "nexus-blobstore-tasks", "nexus-core", "nexus-repository-httpbridge"]
dependencies = "\n".join(f'<dependency><groupId>org.sonatype.nexus.common.components</groupId><artifactId>{name}</artifactId>' + ('<version>${nexus.version}</version>' if name == 'nexus-open-platform' else '') + '<scope>provided</scope></dependency>' for name in deps)
(module / "pom.xml").write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion>
<parent><groupId>org.sonatype.nexus.common.components</groupId><artifactId>nexus-common-components-formats-parent</artifactId><version>3.96.4-01</version><relativePath/></parent>
<artifactId>nexus-repository-{args.format}</artifactId>
<dependencies>{dependencies}
<dependency><groupId>org.apache.commons</groupId><artifactId>commons-compress</artifactId></dependency>
<dependency><groupId>io.swagger</groupId><artifactId>swagger-annotations</artifactId><version>1.6.14</version></dependency>
</dependencies>
</project>
''')
(module / "SOURCE-ORIGIN.json").write_text(json.dumps({"artifact": args.archive.name,
    "sha256": hashlib.sha256(args.archive.read_bytes()).hexdigest(), "license": "EPL-1.0",
    "source": "https://repo.maven.apache.org/maven2/org/sonatype/nexus/plugins/" + args.archive.name.split('-3.')[0] + "/"}, indent=2) + "\n")
print("Imported", args.format, retained, "Java files into", module)
