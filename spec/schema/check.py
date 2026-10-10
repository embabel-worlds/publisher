"""
Validate every example against the $defs entry its file name names:
examples/valid/<Def>.<label>.json must validate, examples/invalid/<Def>.<label>.json must not.
Run: python3 spec/schema/check.py   (needs the jsonschema package)
"""
import json, pathlib, sys
from jsonschema import Draft202012Validator

here = pathlib.Path(__file__).parent
schema = json.loads((here / "protocol.schema.json").read_text())
Draft202012Validator.check_schema(schema)

failures = 0
for expect_valid in (True, False):
    folder = here / "examples" / ("valid" if expect_valid else "invalid")
    for path in sorted(folder.glob("*.json")):
        definition = path.name.split(".")[0]
        if definition not in schema["$defs"]:
            print(f"UNKNOWN DEF {definition}: {path.name}"); failures += 1; continue
        validator = Draft202012Validator({**schema, "$ref": f"#/$defs/{definition}"})
        errors = list(validator.iter_errors(json.loads(path.read_text())))
        ok = (not errors) if expect_valid else bool(errors)
        if not ok:
            failures += 1
            detail = errors[0].message if errors else "validated but should not"
            print(f"FAIL {path.relative_to(here)}: {detail}")
        else:
            print(f"ok   {path.relative_to(here)}")
sys.exit(1 if failures else 0)
