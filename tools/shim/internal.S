.text
.globl ArSession_createWithFeaturesAndTrace
.type ArSession_createWithFeaturesAndTrace, %function
ArSession_createWithFeaturesAndTrace:
    adrp x16, g_ArSession_createWithFeaturesAndTrace
    ldr x16, [x16, #:lo12:g_ArSession_createWithFeaturesAndTrace]
    cbz x16, 1f
    br x16
1:
    ret
.size ArSession_createWithFeaturesAndTrace, .-ArSession_createWithFeaturesAndTrace
