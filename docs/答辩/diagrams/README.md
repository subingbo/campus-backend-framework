# 图表源文件

- `.mmd` 为 Mermaid 源文件；`.svg` 由 `@mermaid-js/mermaid-cli` 渲染，可直接插入 PPT。
- 渲染命令：

```bash
npx --yes @mermaid-js/mermaid-cli -p puppeteer-config.json -i 01-system-architecture.mmd -o 01-system-architecture.svg
```

- `puppeteer-config.json` 需指向本机 Chrome（本机示例）：`{"executablePath":"C:/Program Files/Google/Chrome/Application/chrome.exe","args":["--no-sandbox"]}`。
- 图表只表达架构/流程，不包含真实密钥、坐标或用户数据。
