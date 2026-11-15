package app.morphe.patches.googlephotos.misc.backup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.googlephotos.misc.extension.sharedExtensionPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Upload photos only.
 *
 * Google Photos has no setting to back up photos without videos: the master
 * backup toggle uploads everything in the selected device folders. This patch
 * hooks the backup queue query builder (Luiz.g — the same method the
 * "DCIM & backup control" patch decouples from in_camera_folder) and routes
 * its finished WHERE clause through the extension's PhotosOnlyBackupFilter.
 *
 * At runtime the filter is controlled by the "Upload photos only" toggle in
 * Morphe Settings -> Backup & Storage (default off):
 * - Off: the WHERE clause is returned unchanged, stock behaviour.
 * - On: " AND <media table>.type != 2" is appended, so video items
 *   (media type VIDEO = 2, enum Laahw) are never selected for automatic
 *   upload, while photos keep every existing rule of the same query
 *   (backup folders, backup quality, master toggle). Manual backup of a
 *   single item does not use this queue query and is not affected.
 */
@Suppress("unused")
val uploadPhotosOnlyPatch = bytecodePatch(
    name = "Upload photos only",
    description = "Adds an 'Upload photos only' toggle to Morphe Settings. When enabled, automatic backup uploads photos but never videos: video items are excluded from the backup queue, while photos keep following the existing backup settings (device folders, backup quality and the master backup toggle). Manual backup of a single item is not affected.",
    default = true,
) {
    compatibleWith(AppCompatibilities.GOOGLE_PHOTOS)
    dependsOn(sharedExtensionPatch)

    execute {
        val method = UploadPhotosOnlyQueryFingerprint.methodOrNull
            ?: throw PatchException("Could not find the backup queue query builder method.")

        val classDef = mutableClassDefBy(method.definingClass)
        val mutableMethod = classDef.findMutableMethodOf(method)

        val instructions = mutableMethod.implementation?.instructions?.toList()
            ?: throw PatchException("Backup queue query builder has no instructions.")

        // The builder has a single return-object: the finished WHERE clause.
        val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        if (returnIndex == -1) {
            throw PatchException("Could not find the return of the backup queue query builder.")
        }
        val returnRegister = (instructions[returnIndex] as OneRegisterInstruction).registerA

        // At the return point, pass the finished WHERE clause through the
        // extension filter (which appends the video exclusion when enabled).
        mutableMethod.addInstructions(
            returnIndex,
            """
            invoke-static { v$returnRegister }, Lapp/morphe/extension/shared/patches/PhotosOnlyBackupFilter;->applyPhotosOnlyFilter(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$returnRegister
            """.trimIndent(),
        )

        // At entry, tell the extension which media table this query targets
        // (the first parameter is the Lujd table mapping: LOCAL_MEDIA,
        // LOCAL_PRIVATE_MEDIA or LOCKED_FOLDER), so it can exclude videos on
        // the right type column.
        //
        // The parameter is referenced by its v-register, NOT as p1: this
        // method is obtained via findMutableMethodOf, and for methods proxied
        // that way the inline smali compiler does not receive the parameter
        // list, so a p-register reference fails to compile and the patcher
        // aborts with a misleading "Collection is empty" PatchException.
        // Parameter registers sit at the end of the register frame, so the
        // first parameter's v-register can be computed exactly.
        val implementation = mutableMethod.implementation
            ?: throw PatchException("Backup queue query builder has no implementation.")
        val isStatic = AccessFlags.STATIC.isSet(mutableMethod.accessFlags)
        var parameterRegisterCount = if (isStatic) 0 else 1
        for (type in mutableMethod.parameterTypes) {
            parameterRegisterCount += if (type == "J" || type == "D") 2 else 1
        }
        val firstParameterRegister = implementation.registerCount - parameterRegisterCount +
            if (isStatic) 0 else 1
        mutableMethod.addInstructions(
            0,
            """
            invoke-static { v$firstParameterRegister }, Lapp/morphe/extension/shared/patches/PhotosOnlyBackupFilter;->onBackupQueryStart(Ljava/lang/Object;)V
            """.trimIndent(),
        )
    }
}
