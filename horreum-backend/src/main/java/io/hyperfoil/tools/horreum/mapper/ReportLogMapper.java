package io.hyperfoil.tools.horreum.mapper;

import io.hyperfoil.tools.horreum.api.report.ReportLog;
import io.hyperfoil.tools.horreum.entity.report.ReportLogDAO;

public class ReportLogMapper {
    public static ReportLog from(ReportLogDAO rl) {
        return new ReportLog(rl.getReportId(), rl.level, rl.message);
    }
}
