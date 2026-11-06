package placeholderxposed.xposed

class Constants {
    companion object {
        const val TARGET_PACKAGE = "com.discord"
        const val TARGET_ACTIVITY = "$TARGET_PACKAGE.react_activities.ReactActivity"

        // 352+ dropped ReactActivity, the launcher activity declares onCreate itself.
        val TARGET_ACTIVITIES = listOf(TARGET_ACTIVITY, "$TARGET_PACKAGE.main.MainActivity")

        const val FILES_DIR = "files/pyoncord"
        const val CACHE_DIR = "cache/pyoncord"
        const val MAIN_SCRIPT_FILE = "bundle.js"


        const val LOG_TAG = "Shiggy"

        const val LOADER_NAME = "placeholderxposed"

        const val USER_AGENT = "placeholderxposed"
    }
}
