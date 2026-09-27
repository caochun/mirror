#!/usr/bin/env python3
"""Run after `mvn -f foundry/pom.xml test`. Uses that build's dependency classpaths, never a real database."""
from pathlib import Path
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
foundry = root / "foundry"
paths = [str(path) for path in sorted(foundry.glob("*/target/classes"))]
for report in sorted(foundry.glob("*/target/surefire-reports/TEST-*.xml")):
    for prop in ET.parse(report).findall("./properties/property"):
        if prop.get("name") == "java.class.path":
            paths.extend(prop.get("value", "").split(os.pathsep))
paths = list(dict.fromkeys(path for path in paths if path))
if not paths:
    sys.exit("Build and test Foundry first: mvn -f foundry/pom.xml test")
output = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "platform-review/foundry-audit-current.json"
subprocess.run(["java", "--class-path", os.pathsep.join(paths),
                str(root / "scripts/foundry-audit/ReferenceProbe.java"), str(root / "open-foundry"), str(output)],
               cwd=root, check=True)
