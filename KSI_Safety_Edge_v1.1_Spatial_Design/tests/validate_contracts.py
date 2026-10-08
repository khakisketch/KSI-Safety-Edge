from pathlib import Path
import json, yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
ROOT = Path(__file__).resolve().parents[1]
S = ROOT / 'contracts' / 'schemas'
names = ('mission-snapshot','observation','flight-upload-manifest','operator-annotation','review-finding')
for name in names:
    schema = json.loads((S / (name + '.v1.schema.json')).read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(schema)
    valid = json.loads((ROOT / 'examples' / (name + '.example.json')).read_text(encoding='utf-8'))
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    validator.validate(valid)
    invalid = {**valid, 'schema_version': 999}
    try: validator.validate(invalid)
    except ValidationError: pass
    else: raise AssertionError(f'{name}: invalid schema version accepted')
    invalid = {k:v for k,v in valid.items() if k != 'schema_version'}
    try: validator.validate(invalid)
    except ValidationError: pass
    else: raise AssertionError(f'{name}: missing mandatory schema_version accepted')
api = yaml.safe_load((ROOT / 'contracts' / 'openapi.yaml').read_text(encoding='utf-8'))
assert api['openapi'] == '3.1.0'
assert len(api['paths']) >= 15
for route, operations in api['paths'].items():
    for method, spec in operations.items():
        for p in spec.get('parameters', []):
            assert 'name' in p and 'in' in p, (route,method)
        references = json.dumps(spec,ensure_ascii=False)
        import re
        for file in re.findall(r'\.\/schemas\/([\w.-]+\.json)', references):
            assert (S/file).exists(), (route,file)
print('PASS: schemas=5, examples=5, negative_cases=10, api_paths=',len(api['paths']))
