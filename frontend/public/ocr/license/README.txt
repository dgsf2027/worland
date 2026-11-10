营业执照专用中文高精度模型：Tesseract tessdata_best / chi_sim.traineddata
来源：https://github.com/tesseract-ocr/tessdata_best
许可证：Apache-2.0（见同目录 LICENSE）
浏览器按需加载并缓存；本地 OCR 阶段不调用大模型、不消耗 Token。
用户开启疑难文字复核且后端已配置 LLM 时，低置信度结果额外调用一次视觉模型：
字段区域 JPEG 长边不超过 2200px，辅助文字不超过 6000 字符，输出上限 1024 Token。
无配置或服务失败时保留本地结果并提示人工核对。
模型 SHA256：4FEF2D1306C8E87616D4D3E4C6C67FAF5D44BE3342290CF8F2F0F6E3AA7E735B
与上层目录原有的 fast 模型隔离，不改变合同/发票/回单的既有识别配置。
