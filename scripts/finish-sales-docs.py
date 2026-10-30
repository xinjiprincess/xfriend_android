import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
docs = ROOT / "docs"
string = {"type": "string", "minLength": 1, "maxLength": 256}
refs = {"type": "array", "items": string, "uniqueItems": True}


def resource(properties, required):
    return {"type": "object", "additionalProperties": False, "properties": properties, "required": required}


schema = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "Xfriend mobile export v1",
    "type": "object",
    "additionalProperties": False,
    "required": ["format", "version", "experts", "teams", "skills", "knowledge", "providers"],
    "properties": {
        "format": {"const": "xfriend-mobile-export"},
        "version": {"const": 1},
        "warnings": {"type": "array", "maxItems": 100, "items": {"type": "string", "maxLength": 1000}},
        "source": resource({"user_id": string, "space_id": string}, ["user_id", "space_id"]),
    },
}
definitions = {
    "experts": resource({"id": string, "name": {"type": "string", "minLength": 1, "maxLength": 100}, "prompt": {"type": "string", "minLength": 1, "maxLength": 64000}, "skills": refs, "knowledge": refs}, ["id", "name", "prompt", "skills", "knowledge"]),
    "teams": resource({"id": string, "name": {"type": "string", "minLength": 1, "maxLength": 100}, "members": {**refs, "minItems": 1, "maxItems": 8}}, ["id", "name", "members"]),
    "providers": resource({"id": string, "name": {"type": "string", "minLength": 1, "maxLength": 100}, "base_url": {"type": "string", "format": "uri", "pattern": "^https://"}, "models": {**refs, "minItems": 1}, "api_key": {"type": "string", "maxLength": 4096}}, ["id", "name", "base_url", "models"]),
}
for name in ("skills", "knowledge"):
    definitions[name] = resource({"id": string, "name": {"type": "string", "minLength": 1, "maxLength": 100}, "content": {"type": "string", "minLength": 1, "maxLength": 100000}}, ["id", "name", "content"])
for name, definition in definitions.items():
    schema["properties"][name] = {"type": "array", "maxItems": 500, "items": definition}
schema["$comment"] = "Total UTF-8 bytes <=4 MiB; IDs unique within each array; all expert/team references must exist in the bundle; HTTPS URL cannot contain credentials, query or fragment. Validate these application constraints in addition to JSON Schema."
(docs / "mobile-export-v1.schema.json").write_text(json.dumps(schema, ensure_ascii=False, indent=2), encoding="utf-8")
sample = {
    "format": "xfriend-mobile-export", "version": 1,
    "experts": [{"id": "sales-adviser", "name": "销售顾问", "prompt": "根据原文证据分析客户需求，列出待确认问题和下一步行动。", "skills": ["sales-method"], "knowledge": ["product-guide"]}],
    "teams": [{"id": "sales-team", "name": "客情分析团队", "members": ["sales-adviser"]}],
    "skills": [{"id": "sales-method", "name": "销售方法", "content": "# 跟进方法\n先确认需求、预算、时间和决策人，不编造成交概率。"}],
    "knowledge": [{"id": "product-guide", "name": "产品知识", "content": "这是合成示例资料，请替换为有权使用的产品信息。"}],
    "providers": [{"id": "xiaomi", "name": "小米 MiMo", "base_url": "https://api.xiaomimimo.com/v1", "models": ["mimo-v2.6-flash"], "api_key": ""}],
    "warnings": [],
}
(docs / "mobile-export-v1.example.json").write_text(json.dumps(sample, ensure_ascii=False, indent=2), encoding="utf-8")
print("Export schema and synthetic example written; no credentials included")
