"""Read-only compatibility export; fixed paths, never export desktop/cloud tokens."""

from urllib.parse import quote


async def legacy_export(fetch, include_secrets=False):
    experts, skills, knowledge, warnings = [], [], [], []
    catalogs = await fetch("/api/experts")
    providers = await fetch("/api/providers")
    if not isinstance(catalogs, list) or not isinstance(providers, list):
        raise ValueError("unsupported desktop API")
    if len(catalogs) > 500 or len(providers) > 500:
        raise ValueError("too many resources")
    for summary in catalogs:
        eid = str(summary["id"])
        detail = await fetch("/api/experts/" + quote(eid, safe=""))
        contents = detail.get("file_contents", [])
        files = {
            f["name"]: f["content"]
            for f in contents
            if isinstance(f, dict)
            and isinstance(f.get("name"), str)
            and isinstance(f.get("content"), str)
        }
        prompt_paths = detail.get("prompt_files", [])
        prompt = "\n\n".join(files.get(p, "") for p in prompt_paths).strip()
        if not prompt:
            prompt = "\n\n".join(
                files.get(p, "") for p in ("SOUL.md", "AGENTS.md")
            ).strip()
        if not prompt:
            warnings.append("未导出缺少可读提示词的专家：" + eid)
            continue
        links = []
        for path, text in files.items():
            if (
                path.startswith("skills/")
                and path.endswith("SKILL.md")
                and text.strip()
            ):
                sid = eid + ":" + path
                skills.append(
                    {
                        "id": sid,
                        "name": path.split("/")[-2][:100],
                        "content": text[:100000],
                    }
                )
                links.append(sid)
        label = summary.get("label", {})
        name = (
            (label.get("zh") or label.get("en") or eid)
            if isinstance(label, dict)
            else str(label)
        )
        experts.append(
            {
                "id": eid,
                "name": name[:100],
                "prompt": prompt[:64000],
                "skills": links,
                "knowledge": [],
            }
        )
    exported = []
    for p in providers:
        models = [
            m.get("id")
            for m in p.get("models", [])
            if isinstance(m, dict) and isinstance(m.get("id"), str)
        ]
        if not str(p.get("base_url", "")).startswith("https://") or not models:
            warnings.append(
                "未导出没有 HTTPS 接口或模型列表的配置：" + str(p.get("name", ""))
            )
            continue
        exported.append(
            {
                "id": str(p["id"]),
                "name": str(p["name"]),
                "base_url": p["base_url"],
                "models": models,
                "api_key": p.get("api_key") or "" if include_secrets else "",
            }
        )
    warnings.append(
        "兼容导出包含专家模板及其可读技能和模型配置；完整团队、独立技能包、知识库需电脑版实现 /api/mobile/export。"
    )
    return {
        "format": "xfriend-mobile-export",
        "version": 1,
        "experts": experts,
        "teams": [],
        "skills": skills,
        "knowledge": knowledge,
        "providers": exported,
        "warnings": warnings,
    }
