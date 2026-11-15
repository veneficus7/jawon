package com.jawon.app.ui.wallscene

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.ImageNode
import io.github.sceneview.texture.TextureSampler2D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The case plates with the rail's light in them — see plate.mat and [RailLight]. */
private const val PLATE_MATERIAL_ASSET = "materials/plate.filamat"

/**
 * The case plates' own material when the shelf-edge rail's light is carried by the plates rather
 * than by its 60 heads ([RoomSwitches.Rail]): one `plate.mat` instance per plate, made the first
 * time a plate needs one, and kept in step with the room by [update] — the mode's colour, the
 * rack's rows, the bench gain. A plate keeps its stock instance as well, so a bench build can
 * switch between the two live.
 *
 * Never destroyed here, for the reason the stock instances are not (trap 1): they stay bound to
 * the plates' textures for as long as the scene lives.
 */
class RailPlates(
    private val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val scope: CoroutineScope
) {

    private val material by lazy { materialLoader.createMaterial(PLATE_MATERIAL_ASSET) }

    /**
     * The rail's tables, made the first time a plate needs them: dark at once, so a plate can be
     * bound straight away, and filled in from a background thread — the integral is a tenth of a
     * second on a phone, which on the main thread would stall the room as it opens. A plate shows
     * no rail light for those frames, which pass under the title sequence.
     *
     * The upload runs back on the main thread, where Filament's calls must be; a scope that has
     * ended by then — the room gone — cancels it rather than writing into a dead engine.
     */
    private val tables: List<Texture> by lazy {
        val made = List(RailLight.BINS) { RailLight.darkTexture(engine) }
        scope.launch {
            val values = withContext(Dispatchers.Default) { RailLight.tables() }
            made.forEachIndexed { b, texture -> RibbonLight.halfUpload(engine, texture, values[b]) }
        }
        made
    }

    /** Clamped both ways: the material does not read past the table's edges. */
    private val sampler by lazy {
        TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.CLAMP_TO_EDGE
        )
    }

    private val instances = mutableListOf<MaterialInstance>()

    // What every instance was last given, so a new one starts the same and [update] writes only
    // when something changed. The colour already carries the gain.
    private val colour = floatArrayOf(1f, 1f, 1f)
    private var lux = RailLight.LUX_PER_UNIT
    private var topY = 0f
    private var rows = 0f
    private var mask = 0f

    /** A plate's instance, for its printed [texture], as the stock one is made. */
    fun create(texture: Texture, roughness: Float, reflectance: Float): MaterialInstance =
        materialLoader.createInstance(material).apply {
            setParameter("texture", texture, TextureSampler2D())
            setParameter("metallic", 0f)
            setParameter("roughness", roughness)
            setParameter("reflectance", reflectance)
            tables.forEachIndexed { b, table -> setParameter("rail$b", table, sampler) }
            setParameter("railPitch", WallDimens.ROW_PITCH)
            setParameter("railZ", RoomLighting.RAIL_Z)
            setParameter("railNear", RailLight.Z_NEAR)
            setParameter("railFar", RailLight.Z_FAR)
            setParameter("railReach", RailLight.D_MAX)
            live(this)
        }.also { instances += it }

    /**
     * The room as it is now: the rail's [light] colour (linear, before [gain]), the top row's
     * rail height [railTopY] and the rack's [rowCount], and whether the plates are [masked].
     * Writes to every instance only when one of them changed.
     */
    fun update(light: FloatArray, gain: Float, railTopY: Float, rowCount: Int, masked: Boolean) {
        val newLux = if (gain > 0f) RailLight.LUX_PER_UNIT else 0f
        val newMask = if (masked) 1f else 0f
        val same = (0..2).all { colour[it] == light[it] * gain } &&
            lux == newLux && topY == railTopY && rows == rowCount.toFloat() && mask == newMask
        if (same) return
        for (i in 0..2) colour[i] = light[i] * gain
        lux = newLux
        topY = railTopY
        rows = rowCount.toFloat()
        mask = newMask
        instances.forEach { live(it) }
    }

    private fun live(instance: MaterialInstance) {
        instance.setParameter("railColor", colour[0], colour[1], colour[2])
        instance.setParameter("railLux", lux)
        instance.setParameter("railTopY", topY)
        instance.setParameter("railRows", rows)
        instance.setParameter("mask", mask)
    }
}

/**
 * Binds this plate to the material the rail's [mode] wants — [stock] while the rail's heads light
 * it, its `plate.mat` instance when it carries the rail's light itself — making that instance the
 * first time it is needed. Returns the `plate.mat` instance, made or as it was ([plate]).
 *
 * **Called after composition, never from the node's `apply`**: SceneView 4.32 runs an `ImageNode`'s
 * `apply` only when it makes the node (checked in the bytecode, `SceneScope.ImageNode`), so a
 * binding made there would never follow a switch of [mode]. Nothing is bound until the node's own
 * `apply` has lit it with [stock].
 */
internal fun ImageNode.bindRail(
    mode: RoomSwitches.Rail,
    stock: MaterialInstance?,
    plate: MaterialInstance?,
    plates: RailPlates,
    roughness: Float,
    reflectance: Float
): MaterialInstance? {
    stock ?: return plate
    val made = if (mode.bakedPlates) plate ?: plates.create(texture, roughness, reflectance) else plate
    val wanted = if (mode.bakedPlates) made!! else stock
    if (materialInstance !== wanted) materialInstance = wanted
    return made
}
