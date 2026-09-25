package com.datagrail.consent.demo.tester

import android.content.Context
import android.text.format.DateUtils
import com.datagrail.consent.BuildConfig
import com.datagrail.consent.demo.R
import com.datagrail.consent.demo.tester.ConfigUrlLoadResult.Failure

/** Title and body shown in the tester's error card. */
fun Failure.userFacing(context: Context): Pair<String, String> =
    when (this) {
        Failure.InvalidUrl ->
            context.getString(R.string.tester_error_invalid_url_title) to
                context.getString(R.string.tester_error_invalid_url_body)
        is Failure.Expired -> {
            val body = context.getString(R.string.tester_error_expired_body)
            val expiredAt =
                expiresAtMillis?.let {
                    " " + context.getString(R.string.tester_error_expired_at, formatDeviceTime(context, it))
                }.orEmpty()
            context.getString(R.string.tester_error_expired_title) to body + expiredAt
        }
        Failure.UrlAltered ->
            context.getString(R.string.tester_error_url_altered_title) to
                context.getString(R.string.tester_error_url_altered_body)
        is Failure.NotFoundOrDenied ->
            context.getString(R.string.tester_error_not_found_title) to
                context.getString(R.string.tester_error_not_found_body)
        is Failure.Unreachable ->
            context.getString(R.string.tester_error_unreachable_title) to
                context.getString(R.string.tester_error_unreachable_body, detail)
        is Failure.Http ->
            context.getString(R.string.tester_error_http_title) to
                if (code == null) {
                    context.getString(R.string.tester_error_http_body, status)
                } else {
                    context.getString(R.string.tester_error_http_body_with_code, status, code)
                }
        is Failure.UnsupportedSchema ->
            context.getString(R.string.tester_error_unsupported_schema_title) to
                context.getString(R.string.tester_error_unsupported_schema_body, config, BuildConfig.LIBRARY_VERSION, sdk)
        is Failure.Parse ->
            context.getString(R.string.tester_error_parse_title) to
                context.getString(R.string.tester_error_parse_body, detail)
        is Failure.Invalid ->
            context.getString(R.string.tester_error_parse_title) to
                context.getString(R.string.tester_error_parse_body, detail)
    }

fun formatDeviceTime(
    context: Context,
    epochMillis: Long,
): String =
    DateUtils.formatDateTime(
        context,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
    )
