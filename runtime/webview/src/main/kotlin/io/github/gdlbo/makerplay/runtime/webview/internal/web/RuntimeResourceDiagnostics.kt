package io.github.gdlbo.makerplay.runtime.webview.internal.web

internal fun reportRuntimeHttpFailure(
    report: (String, Map<String, String>) -> Unit,
    requestFields: Map<String, String>,
    mainFrame: Boolean,
    statusCode: Int,
    reason: String,
) {
    report(
        "runtime.resource.http_error",
        requestFields + runtimeResourceFailureFields(
            mainFrame,
            mapOf("statusCode" to statusCode.toString(), "reason" to reason),
        ),
    )
}

internal fun runtimeResourceFailureFields(
    mainFrame: Boolean,
    cause: Map<String, String>,
): Map<String, String> = cause + mapOf(
    "stage" to "resource-loading",
    "action" to if (mainFrame) "entry-load-failed" else "resource-load-failed",
)
