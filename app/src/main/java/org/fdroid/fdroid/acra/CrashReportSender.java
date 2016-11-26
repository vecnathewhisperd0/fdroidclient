
package org.belos.belmarket.acra;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.annotation.NonNull;

import org.acra.ACRAConstants;
import org.acra.ReportField;
import org.acra.collections.ImmutableSet;
import org.acra.collector.CrashReportData;
import org.acra.config.ACRAConfiguration;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;

import java.util.Iterator;

public class CrashReportSender implements ReportSender {

    private final ACRAConfiguration config;

    public CrashReportSender(ACRAConfiguration config) {
        this.config = config;
    }

    public void send(@NonNull Context context, @NonNull CrashReportData errorContent)
            throws ReportSenderException {
        Intent emailIntent = new Intent("android.intent.action.SENDTO");
        emailIntent.setData(Uri.fromParts("mailto", this.config.mailTo(), (String) null));
        emailIntent.addFlags(268435456);
        String[] subjectBody = this.buildSubjectBody(context, errorContent);
        emailIntent.putExtra("android.intent.extra.SUBJECT", subjectBody[0]);
        emailIntent.putExtra("android.intent.extra.TEXT", subjectBody[1]);
        context.startActivity(emailIntent);
    }

    private String[] buildSubjectBody(Context context, CrashReportData errorContent) {
        ImmutableSet fields = this.config.getReportFields();
        if (fields.isEmpty()) {
            fields = new ImmutableSet<ReportField>(ACRAConstants.DEFAULT_MAIL_REPORT_FIELDS);
        }

        String subject = context.getPackageName() + " Crash Report";
        StringBuilder builder = new StringBuilder();
        Iterator var4 = fields.iterator();

        while (var4.hasNext()) {
            ReportField field = (ReportField) var4.next();
            builder.append(field.toString()).append('=');
            builder.append(errorContent.get(field));
            builder.append('\n');
            if ("STACK_TRACE".equals(field.toString())) {
                String stackTrace = errorContent.get(field);
                if (stackTrace != null) {
                    subject = context.getPackageName() + ": "
                            + stackTrace.substring(0, stackTrace.indexOf('\n'));
                    if (subject.length() > 72) {
                        subject = subject.substring(0, 72);
                    }
                }
            }
        }

        return new String[]{subject, builder.toString()};
    }
}
