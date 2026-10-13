#!/usr/bin/env python3
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

provenance, pom = map(Path, sys.argv[1:])
version = json.loads(provenance.read_text())["version"]
namespace = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", namespace)
tree = ET.parse(pom)
tree.getroot().find(f"{{{namespace}}}parent/{{{namespace}}}version").text = version
tree.write(pom, encoding="utf-8", xml_declaration=True)
