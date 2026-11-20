package com.example.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.core.LeviLogger
import com.example.model.FileManagerEntry
import java.io.File

object LeviFileManager {

    private val previewExtensions = setOf(
        "txt", "json", "log", "cfg", "properties", "mcmeta", "lang", "xml"
    )

    fun listAppFiles(context: Context, directory: File): List<FileManagerEntry> {
        val rootCanonical = context.filesDir.canonicalFile
        val target = directory.canonicalFile
        if (!target.path.startsWith(rootCanonical.path)) return emptyList()
        if (!target.exists() || !target.isDirectory) return emptyList()

        return target.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { f ->
                val ext = f.extension.lowercase()
                val canPreview = f.isFile && (ext in previewExtensions || f.length() in 1..65_536 && ext.isEmpty())
                FileManagerEntry(
                    id = f.absolutePath,
                    name = f.name,
                    pathOrUri = f.absolutePath,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else countFilesSize(f),
                    lastModifiedMs = f.lastModified(),
                    mimeType = if (f.isDirectory) "inode/directory" else ext,
                    canDelete = f.canonicalPath != File(rootCanonical, "logs").canonicalPath,
                    canPreview = canPreview
                )
            }
    }

    fun readAppTextFilePreview(context: Context, filePath: String, maxChars: Int = 8192): Result<String> {
        return runCatching {
            val rootCanonical = context.filesDir.canonicalFile
            val target = File(filePath).canonicalFile
            require(target.path.startsWith(rootCanonical.path)) { "Access outside app filesDir denied" }
            require(target.isFile) { "Not a regular file" }
            target.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(maxChars)
                val read = reader.read(buf)
                if (read <= 0) "(empty file)" else String(buf, 0, read)
            }
        }
    }

    fun deleteAppFileOrDir(context: Context, path: String, logger: LeviLogger? = null): Boolean {
        return runCatching {
            val rootCanonical = context.filesDir.canonicalFile
            val target = File(path).canonicalFile
            if (!target.path.startsWith(rootCanonical.path) || target == rootCanonical) return false
            val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (ok) {
                logger?.info("FILE_MGR", "Deleted app file/folder: ${target.name}")
            }
            ok
        }.getOrDefault(false)
    }

    fun listSafDirectory(
        context: Context,
        treeUri: Uri,
        documentId: String = DocumentsContract.getTreeDocumentId(treeUri)
    ): List<FileManagerEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        val entries = mutableListOf<FileManagerEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val flagsIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "item"
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else ""
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val modified = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                    val flags = if (flagsIdx >= 0 && !cursor.isNull(flagsIdx)) cursor.getInt(flagsIdx) else 0
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val canDelete = (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                    val canPreview = !isDir && (ext in previewExtensions || mime.startsWith("text/"))

                    entries.add(
                        FileManagerEntry(
                            id = docId,
                            name = name,
                            pathOrUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                            isDirectory = isDir,
                            sizeBytes = size,
                            lastModifiedMs = modified,
                            mimeType = mime,
                            canDelete = canDelete,
                            canPreview = canPreview
                        )
                    )
                }
            }
        }
        return entries.sortedWith(compareByDescending<FileManagerEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readSafTextPreview(context: Context, docUri: Uri, maxChars: Int = 8192): Result<String> {
        return runCatching {
            context.contentResolver.openInputStream(docUri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buf = CharArray(maxChars)
                val read = reader.read(buf)
                if (read <= 0) "(empty file)" else String(buf, 0, read)
            } ?: error("Cannot open document stream")
        }
    }

    fun createSafSubfolder(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        folderName: String,
        logger: LeviLogger? = null
    ): Boolean {
        val clean = folderName.trim().replace('/', '_')
        if (clean.isEmpty()) return false
        return runCatching {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                clean
            )
            if (created != null) {
                logger?.info("FILE_MGR", "Created SAF folder '$clean'")
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun deleteSafDocument(context: Context, docUri: Uri, logger: LeviLogger? = null): Boolean {
        return runCatching {
            val ok = DocumentsContract.deleteDocument(context.contentResolver, docUri)
            if (ok) {
                logger?.info("FILE_MGR", "Deleted SAF document: $docUri")
            }
            ok
        }.getOrDefault(false)
    }

    private fun countFilesSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }
}
