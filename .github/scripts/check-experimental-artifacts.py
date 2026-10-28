#!/usr/bin/env python3
"""Check the built experimental JAR's metadata and mandatory package formats."""

import hashlib
import re
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile

libs = Path("HMCL/build/libs")
jars = list(libs.glob("HMCL-*.jar"))
assert len(jars) == 1, f"Expected one launcher JAR, found {jars}"
jar = jars[0]
assert re.fullmatch(r"HMCL-\d[\w.]*-\d+\.exp\.[0-9a-f]{7}\.jar", jar.name), jar.name
with ZipFile(jar) as archive:
    properties = archive.read("assets/hmcl.properties").decode("utf-8").replace("\\:", ":").replace("\\=", "=")
    values = dict(line.split("=", 1) for line in properties.splitlines() if "=" in line and not line.startswith("#"))
    assert values["hmcl.version.type"] == "experimental", properties
    assert values["hmcl.version"] == jar.stem.removeprefix("HMCL-"), properties
    assert values["hmcl.update_source.override"] == "https://github.com/Churk-Ben/HMCL-experimental/releases/latest/download/update-exp.json", properties
    assert values["hmcl.manual_update_url"] == "https://github.com/Churk-Ben/HMCL-experimental/releases", properties
for extension in ("jar", "exe", "sh", "deb", "rpm"):
    artifact = jar.with_suffix("." + extension)
    assert artifact.is_file(), f"Missing mandatory artifact: {artifact}"
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == Path(str(artifact) + ".sha256").read_text().strip(), artifact
assert subprocess.check_output(["dpkg-deb", "--field", str(jar.with_suffix(".deb")), "Package"], text=True).strip() == "hmcl-exp"
# RPM's main header follows the lead and the eight-byte-aligned signature header.
rpm = jar.with_suffix(".rpm").read_bytes()
assert rpm[:4] == bytes.fromhex("edabeedb"), "Invalid RPM lead"
signature_entries, signature_size = struct.unpack_from(">II", rpm, 104)
header = (96 + 16 + 16 * signature_entries + signature_size + 7) & ~7
assert rpm[header:header + 4] == bytes.fromhex("8eade801"), "Invalid RPM header"
entries, _ = struct.unpack_from(">II", rpm, header + 8)
store = header + 16 + entries * 16
names = []
for index in range(entries):
    tag, kind, offset, count = struct.unpack_from(">IIII", rpm, header + 16 + index * 16)
    if tag == 1000:  # RPMTAG_NAME, RPM_STRING_TYPE
        assert kind == 6 and count == 1
        names.append(rpm[store + offset:].split(b"\0", 1)[0])
assert names == [b"hmcl-exp"], names
print(f"Verified experimental channel, update source, version and five artifact checksums: {jar.stem}")
