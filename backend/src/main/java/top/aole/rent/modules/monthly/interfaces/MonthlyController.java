package top.aole.rent.modules.monthly.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.monthly.domain.MonthlyReport;
import top.aole.rent.modules.monthly.dto.MonthlyDtos;
import top.aole.rent.modules.monthly.service.MonthlyAnalysisService;
import top.aole.rent.modules.monthly.service.MonthlyCalendarService;
import top.aole.rent.modules.monthly.service.MonthlyExportService;
import top.aole.rent.modules.monthly.service.MonthlyReportScheduler;
import top.aole.rent.modules.monthly.service.MonthlyReportService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 月度报表接口(M3-07,DESIGN_DOC §4.3 六件套/七节/财务日历 + 一键导出)。
 *
 * <p>核心入口 GET /api/rent/monthly-report?period=&export=xlsx:
 * export 空 → 返回六件套 JSON;export=xlsx → 下载 Excel;export=docx → 下载七节报告 Word。
 * 全部只读聚合;分配表按角色可见(§4.5)。
 */
@Api(tags = "月度报表 · 六件套/七节报告/财务日历/导出")
@RestController
@RequestMapping("/rent")
@RequiredArgsConstructor
public class MonthlyController {

    private final MonthlyReportService monthlyReportService;
    private final MonthlyAnalysisService monthlyAnalysisService;
    private final MonthlyCalendarService monthlyCalendarService;
    private final MonthlyExportService monthlyExportService;
    private final MonthlyReportScheduler monthlyReportScheduler;

    @ApiOperation("月度报表包:export 空=六件套 JSON;export=xlsx 下载 Excel;export=docx 下载七节报告 Word")
    @GetMapping("/monthly-report")
    public Object monthlyReport(@RequestParam(required = false) String period,
                                @RequestParam(required = false) String export) {
        if ("xlsx".equalsIgnoreCase(export)) {
            byte[] data = monthlyExportService.exportExcel(period);
            return download(data, "月度报表包-" + safe(period) + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        if ("docx".equalsIgnoreCase(export)) {
            byte[] data = monthlyExportService.exportWord(period);
            return download(data, "月度经营分析报告-" + safe(period) + ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        return R.ok(monthlyReportService.buildPackage(period));
    }

    @ApiOperation("第七件《月度经营分析报告》:固定七节,规则出数字(引 package_json·带溯源)+ AI mock 起草综述;force 重新起草")
    @GetMapping("/monthly-report/analysis")
    public R<MonthlyDtos.AnalysisReport> analysis(@RequestParam(required = false) String period,
                                                  @RequestParam(defaultValue = "false") boolean force) {
        return R.ok(monthlyAnalysisService.analyze(period, force));
    }

    @ApiOperation("财务月度工作日历:1/2/3/5 日各步骤自动完成/待人工状态看板")
    @GetMapping("/monthly-report/calendar")
    public R<MonthlyDtos.CalendarBoard> calendar(@RequestParam(required = false) String period) {
        return R.ok(monthlyCalendarService.board(period));
    }

    @ApiOperation("生成并落库报表包快照(手动触发,与 2 日 cron 同一执行体)")
    @PostMapping("/monthly-report/generate")
    public R<MonthlyReport> generate(@RequestParam(required = false) String period) {
        return R.ok(monthlyReportScheduler.runMonthlyReportCron(period, "manual"));
    }

    private ResponseEntity<byte[]> download(byte[] data, String filename, String contentType) {
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            encoded = filename;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"report\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(data.length);
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }

    private String safe(String period) {
        return period == null || period.isEmpty() ? "latest" : period;
    }
}
