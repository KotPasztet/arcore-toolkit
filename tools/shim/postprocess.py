s = open('shim.c').read()
if 'g_ArCoreApk_getSymbolTable' not in s:
    s = s.replace('static void *real_handle;',
                  'static void *real_handle;\n__attribute__((visibility("hidden"))) void *g_ArCoreApk_getSymbolTable;\n__attribute__((visibility("hidden"))) void *g_ArSession_createWithFeaturesAndTrace;')
if 'g_ArCoreApk_getSymbolTable = dlsym' not in s:
    s = s.replace('    real_ArAnchor_detach = ',
                  '    g_ArCoreApk_getSymbolTable = dlsym(real_handle, "ArCoreApk_getSymbolTable");\n'
                  '    g_ArSession_createWithFeaturesAndTrace = dlsym(real_handle, "ArSession_createWithFeaturesAndTrace");\n'
                  '    real_ArAnchor_detach = ')
if 'dladdr((void *)shim_init' not in s:
    block = '''    char path[1024];
    Dl_info info;
    if (dladdr((void *)shim_init, &info) && info.dli_fname) {
        strncpy(path, info.dli_fname, sizeof(path) - 1);
        path[sizeof(path) - 1] = 0;
        char *slash = strrchr(path, '/');
        if (slash) {
            strncpy(slash + 1, "libarcore_sdk_c_real.so", sizeof(path) - (slash + 1 - path) - 1);
            path[sizeof(path) - 1] = 0;
            real_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        }
    }
    if (!real_handle) real_handle = dlopen("libarcore_sdk_c_real.so", RTLD_NOW | RTLD_LOCAL);
'''
    s = s.replace('    real_handle = dlopen("libarcore_sdk_c_real.so", RTLD_NOW | RTLD_LOCAL);\n', block)
open('shim.c','w').write(s)
print("postprocessed")
