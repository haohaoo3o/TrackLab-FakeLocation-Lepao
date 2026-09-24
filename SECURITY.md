# 安全政策 / Security Policy

## 支持范围

安全修复通常面向默认分支的最新代码。历史提交、个人分支、修改版 APK、第三方 SDK 服务可用性以及已停止维护的版本不保证获得修复。

## 私下报告漏洞

安全漏洞及任何包含敏感信息的问题必须通过本仓库即将启用的 **GitHub Private Vulnerability Reporting** 私下提交：

<https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/security/advisories/new>

请勿在公开 Issue、Discussion、Pull Request、日志或截图中披露尚未修复的漏洞、API Key、签名材料、精确位置、轨迹文件、设备标识符或其他敏感信息。公开 Issue 只用于不含敏感信息的常规功能缺陷、构建问题、权限配置疑问和功能建议。

该 URL 在同名 GitHub 仓库创建并启用 Private Vulnerability Reporting 后可用。仓库创建前或功能尚未启用时请保留报告材料，待私密渠道可用后提交；为保持预定私密地址稳定，请勿修改该 URL，也不要改用公开 Issue 传递敏感内容。

## 报告内容

为便于确认和修复，请尽量提供：

- 受影响的提交或版本；
- Android 版本与必要的运行环境信息；
- 最小复现步骤或概念验证；
- 实际结果、预期结果和安全影响；
- 建议的缓解或修复方式；
- 是否已向第三方依赖维护者报告。

请在提交前删除真实高德 Key、签名证书、口令、精确轨迹、个人信息和不必要的设备标识符。

## 处理流程

维护者会在合理时间内确认收到报告、评估影响、协调修复和发布说明。修复发布前，请给予维护者合理的处理时间并避免公开漏洞细节。若问题属于高德 SDK、Android 平台或其他依赖，维护者可能要求报告者同时遵循相应供应商的安全响应流程。

## 非敏感问题

不含敏感信息的常规问题可提交到公开 [Issues](https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/issues)。提交前请确认内容不包含安全漏洞细节、凭据、签名材料、精确位置、轨迹文件、个人信息或设备标识符。

## Security policy in English

Submit vulnerabilities and all sensitive reports through the repository's planned GitHub Private Vulnerability Reporting URL:

<https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/security/advisories/new>

The URL becomes usable after the GitHub repository exists and Private Vulnerability Reporting is enabled. Keep the URL unchanged. Until then, retain sensitive report material rather than posting it publicly. Public Issues are only for non-sensitive bugs, build questions, configuration questions, and feature requests.
