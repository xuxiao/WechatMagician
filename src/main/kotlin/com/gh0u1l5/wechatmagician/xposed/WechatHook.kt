package com.gh0u1l5.wechatmagician.xposed

import android.content.ContentValues
import android.content.res.XModuleResources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gh0u1l5.wechatmagician.R
import com.gh0u1l5.wechatmagician.util.C
import com.gh0u1l5.wechatmagician.util.ImageUtil
import com.gh0u1l5.wechatmagician.util.MessageUtil
import com.gh0u1l5.wechatmagician.xposed.MessageCache.WechatMessage
import de.robv.android.xposed.*
import de.robv.android.xposed.XposedBridge.*
import de.robv.android.xposed.XposedHelpers.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.*

// WechatHook contains the entry points and all the hooks.
class WechatHook : IXposedHookZygoteInit, IXposedHookLoadPackage {

    data class LocalizedResources (
            val easter_egg: String,
            val label_deleted: String,
            val label_recalled: String,
            val bitmap_recalled: Bitmap
    )

    companion object {
        lateinit var pkg: WechatPackage
        lateinit var res: LocalizedResources
        lateinit var loader: ClassLoader
    }

    // Hook for initializing localized resources.
    override fun initZygote(param: IXposedHookZygoteInit.StartupParam?) {
        val _res = XModuleResources.createInstance(param?.modulePath, null)

        val easter_egg = _res.getString(R.string.easter_egg)
        val label_deleted = _res.getString(R.string.label_deleted)
        val label_recalled = _res.getString(R.string.label_recalled)

        val imgName = "image_recall_${_res.getString(R.string.language)}.jpg"
        val imgStream = _res.assets.open(imgName)
        val bitmap_recalled = BitmapFactory.decodeStream(imgStream)
        imgStream.close()

        res = LocalizedResources(easter_egg, label_deleted, label_recalled, bitmap_recalled)
    }

    // Hook for hacking Wechat application.
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.tencent.mm") {
            return
        }

        pkg = WechatPackage(param)
        loader = param.classLoader

        tryHook(this::hookDatabase, {
            pkg.SQLiteDatabaseClass = ""
        })
        tryHook(this::hookXMLParse, {
            pkg.XMLParserClass = ""
        })
        tryHook(this::hookImgStorage, {
            pkg.ImgStorageClass = ""
        })
    }

    private fun tryHook(hook: () -> Unit, cleanup: (Throwable) -> Unit) {
        try { hook() } catch (e: Throwable) { log("HOOK => $e"); cleanup(e) }
    }

    private fun hookImgStorage() {
        if (pkg.ImgStorageClass == "") {
            return
        }

        // Analyze dynamically to find the global image storage instance
        val ImgInfoStorageClass = findClass(pkg.ImgStorageClass, loader)
        hookAllConstructors(ImgInfoStorageClass, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {
                if (pkg.ImgStorageObject !== param.thisObject) {
                    pkg.ImgStorageObject = param.thisObject
                }
            }
        })

//        findAndHookMethod(ImgStorageClass, pkg.ImgStorageLoadMethod, C.String, C.String, C.String, C.Boolean, object : XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun afterHookedMethod(param: MethodHookParam) {
//                val imgId = param.args[0] as String?
//                val prefix = param.args[1] as String?
//                val suffix = param.args[2] as String?
//                log("IMG => imgId = $imgId, prefix = $prefix, suffix = $suffix")
//            }
//        })

        // Hook FileOutputStream to prevent Wechat from overwriting disk cache
        findAndHookConstructor("java.io.FileOutputStream", loader, C.File, C.Boolean, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val path = (param.args[0] as File).path
                synchronized(ImageUtil.blockTable) {
                    if (path in ImageUtil.blockTable) {
                        param.throwable = IOException()
                    }
                }
            }
        })
    }

    private fun hookXMLParse() {
        if (pkg.XMLParserClass == "" || pkg.XMLParseMethod == "") {
            return
        }

        // Hook XML Parser for the status bar easter egg
        findAndHookMethod(pkg.XMLParserClass, loader, pkg.XMLParseMethod, C.String, C.String, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {
//                val xml = param.args[0] as String?
//                val tag = param.args[1] as String?
//                log("XML => xml = $xml, tag = $tag")

                @Suppress("UNCHECKED_CAST")
                param.result = (param.result as MutableMap<String, String?>?)?.apply {
                    if (this[".sysmsg.\$type"] != "revokemsg") {
                        return
                    }
                    val msgtag = ".sysmsg.revokemsg.replacemsg"
                    val msg = this[msgtag] ?: return
                    if (msg.startsWith("\"")) {
                        this[msgtag] = MessageUtil.applyEasterEgg(msg, res.easter_egg)
                    }
                }
            }
        })
    }

    private fun hookDatabase() {
        if (pkg.SQLiteDatabaseClass == ""){
            return
        }

        // Hook SQLiteDatabase.insert to update MessageCache
        findAndHookMethod(pkg.SQLiteDatabaseClass, loader, "insertWithOnConflict", C.String, C.String, C.ContentValues, C.Int, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val table = param.args[0] as String?
                val values = param.args[2] as ContentValues? ?: return
//                log("DB => insert table = $table, values = $values")

                if (table == "message") {
                    if (values["isSend"] == 1) {
                        return // ignore the message sent by myself
                    }
                    val msgId = values["msgId"] as Long
                    MessageCache[msgId] = WechatMessage(
                            values["type"] as Int,
                            values["talker"] as String,
                            values["content"] as String?,
                            values["imgPath"] as String?)
                }
            }
        })

        // Hook SQLiteDatabase.update to prevent Wechat from recalling messages or deleting moments
        findAndHookMethod(pkg.SQLiteDatabaseClass, loader, "updateWithOnConflict", C.String, C.ContentValues, C.String, C.StringArray, C.Int, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val table = param.args[0] as String?
                val values = param.args[1] as ContentValues?
//                val whereClause = param.args[2] as String?
//                val whereArgs = param.args[3] as Array<*>?
//                log("DB => update table = $table, values = $values, whereClause = $whereClause, whereArgs = ${MessageUtil.argsToString(whereArgs)}")

                when (table) {
                    "message" -> values?.apply { // recall message
                        if (!containsKey("type") || this["type"] != 10000) {
                            return
                        }
                        val msgId = this["msgId"] as Long
                        MessageCache[msgId]?.let {
                            handleMessageRecall(it, values)
                        }
                    }
                    "SnsInfo" -> values?.apply { // delete moment
                        if (!containsKey("sourceType") || this["sourceType"] != 0) {
                            return
                        }
                        val content =  values["content"] as ByteArray?
                        handleMomentDelete(content, values)
                    }
                    "SnsComment" -> values?.apply { // delete moment comment
                        if (!containsKey("type") || this["type"] == 1) {
                            return
                        }
                        if (!containsKey("commentflag") || this["commentflag"] != 1) {
                            return
                        }
                        val curActionBuf = this["curActionBuf"] as ByteArray?
                        handleCommentDelete(curActionBuf, values)
                    }
                }
            }
        })

//        findAndHookMethod(pkg.SQLiteDatabaseClass, loader, "delete", C.String, C.String, C.StringArray, object : XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun beforeHookedMethod(param: MethodHookParam) {
//                val table = param.args[0] as String?
//                val whereClause = param.args[1] as String?
//                val whereArgs = param.args[2] as Array<*>?
//                log("DB => delete table = $table, whereClause = $whereClause, whereArgs = ${MessageUtil.argsToString(whereArgs)}")
//            }
//        })

//        findAndHookMethod(pkg.SQLiteDatabaseClass, loader, "executeSql", C.String, C.ObjectArray, object : XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun beforeHookedMethod(param: MethodHookParam) {
//                val sql = param.args[0] as String?
//                val bindArgs = param.args[1] as Array<*>?
//                log("DB => executeSql sql = $sql, bindArgs = ${MessageUtil.argsToString(bindArgs)}")
//            }
//        })
    }

    // handleMessageRecall notifies user that someone has recalled the message
    private fun handleMessageRecall(origin: WechatMessage, values: ContentValues) {
        // Split speaker and message for chatrooms
        val speaker: String?; var message: String?
        if (origin.talker.contains("chatroom")) {
            val len = (origin.content?.indexOf(":\n") ?: 0) + 2
            speaker = origin.content?.take(len)
            message = origin.content?.drop(len)
        } else {
            speaker = ""; message = origin.content
        }

        // Modify runtime data to notify user
        values.remove("type")
        values.remove("content")
        when (origin.type) {
            1 -> {
                message = MessageUtil.notifyMessageRecall(res.label_recalled, message!!)
                values.put("content", speaker + message)
            }
            3 -> {
                ImageUtil.replaceThumbnail(origin.imgPath!!, res.bitmap_recalled)
            }
            49 -> {
                message = MessageUtil.notifyLinkRecall(res.label_recalled, message!!)
                values.put("content", speaker + message)
            }
        }
    }

    // handleMomentDelete notifies user that someone has deleted the given moment
    private fun handleMomentDelete(content: ByteArray?, values: ContentValues) {
        MessageUtil.notifyInfoDelete(res.label_deleted, content)?.let {
            values.remove("sourceType")
            values.put("content", it)
        }
    }

    // handleCommentDelete notifies user that someone has deleted the given comment in moments
    private fun handleCommentDelete(curActionBuf: ByteArray?, values: ContentValues) {
        MessageUtil.notifyCommentDelete(res.label_deleted, curActionBuf)?.let {
            values.remove("commentflag")
            values.put("curActionBuf", it)
        }
    }
}