---
title: "Apache Camel Security Advisory - CVE-2024-22369"
date: 2024-02-19T09:25:42+02:00
url: /security/CVE-2024-22369.html
draft: false
type: security-advisory
cve: CVE-2024-22369
severity: HIGH
summary: "Unsafe Deserialization from JDBCAggregationRepository"
description: "This vulnerability allows an attacker to execute arbitrary code via JDBC deserialization."
mitigation: "Users are recommended to upgrade to version 3.21.4, 3.22.1, 4.0.4 or 4.4.0."
credit: "Ziyang Chen from HuaWei"
affected: "From 3.0.0 before 3.21.4, from 4.0.0 before 4.0.4"
fixed: "3.21.4, 3.22.1, 4.0.4, 4.4.0"
---

This issue was reported via [CAMEL-20303](https://issues.apache.org/jira/browse/CAMEL-20303).
