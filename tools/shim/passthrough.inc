/* Passthrough shim: every ARCore call is forwarded to the real library.
 * Only the internal (non-header) symbol accessor has to be provided here. */

static void trace(const char *name) { (void)name; }

extern void *g_ArCoreApk_getSymbolTable;

void *ArCoreApk_getSymbolTable(void) {
    if (g_ArCoreApk_getSymbolTable) {
        return ((void *(*)(void))g_ArCoreApk_getSymbolTable)();
    }
    return NULL;
}
