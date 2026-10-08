from pathlib import Path
import copy,json
from jsonschema import Draft202012Validator, FormatChecker

ROOT=Path(__file__).resolve().parents[1]
S=ROOT/'contracts'/'schemas';E=ROOT/'examples'
names=['spatial-relation','spatial-summary','risk-policy']
for name in names:
    schema=json.loads((S/f'{name}.v1.schema.json').read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(schema)
    item=json.loads((E/f'{name}.v1.example.json').read_text(encoding='utf-8'))
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(item)
    print('PASS:',name,'schema + positive example')

r_schema=json.loads((S/'spatial-relation.v1.schema.json').read_text(encoding='utf-8'))
r=json.loads((E/'spatial-relation.v1.example.json').read_text(encoding='utf-8'))
validator=Draft202012Validator(r_schema,format_checker=FormatChecker())
for change in [{'metric_status':'UNAVAILABLE','distance_m':4.2,'distance_interval_m':None}, {'metric_status':'VERIFIED','distance_m':None,'distance_interval_m':None}, {'geometry_level':'FAKE'}]:
    x=copy.deepcopy(r);x.update(change)
    assert list(validator.iter_errors(x)),f'negative unexpectedly valid: {change}'
print('PASS: 3 negative tests')
# Cross-field checks beyond JSON Schema
assert r['distance_interval_m'] is None or r['distance_interval_m'][0] <= r['distance_m'] <= r['distance_interval_m'][1]
assert r['metric_status']!='VERIFIED' or r['geometry_level']=='METRIC_VERIFIED'
print('PASS: semantic consistency')
