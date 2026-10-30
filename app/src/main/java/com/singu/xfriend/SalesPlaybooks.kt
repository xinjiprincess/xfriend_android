package com.singu.xfriend

internal val salesSkills = listOf(
    Skill("sales-playbook-needs-v1", "SPIN 需求访谈", "按背景S、问题P、影响I、收益N整理已知事实；每项附原文证据。给出3个开放式追问，区分客户明确需求与销售假设；不要替客户回答。"),
    Skill("sales-playbook-decision-v1", "BANT 与决策地图", "核对预算、决策权、需求和时间窗口；补充使用者、影响者、审批人及采购步骤。缺失项写待确认，给出核验问题，不把联系人自动当作决策人。"),
    Skill("sales-playbook-objection-v1", "异议处理与谈判演练", "对每个异议记录客户原话、潜在原因、澄清问题、价值证据、两种尊重客户的回复。让步采用条件交换，不虚构优惠、竞争事实或交付能力；给下一次通话的开场和收尾。"),
    Skill("sales-playbook-action-v1", "SMART 跟进与商机推进", "建议行动必须包含目标、负责人、日期及验证结果；依据真实承诺判断商机阶段，列出停滞原因和推进门槛。生成可供用户确认的行动清单，不声称已写入CRM或发送消息。"),
    Skill("sales-playbook-risk-v1", "付款交付与流失核查", "检查付款、交付、合同承诺、竞品和关系中断信号；按证据、影响、核验问题、缓解行动输出。对客户人格、健康或敏感属性不做推断，不编造风险概率。"),
    Skill("sales-playbook-review-v1", "销售复盘与会前准备", "用事实-判断-行动结构复盘；对照上次承诺逐项核实，区分客户与销售说法。输出60秒摘要、下一次会面议程、三项关键问题、下一步草案；保留团队分歧。")
)
internal val salesSkillBindings = mapOf("sales-needs" to 0, "sales-relationship" to 1, "sales-objection" to 2, "sales-opportunity" to 3, "sales-risk" to 4, "sales-lead" to 5)
