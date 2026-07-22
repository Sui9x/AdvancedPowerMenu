//DrawableUtils 1.1.0

package com.sui.utils

import android.app.*
import android.content.*
import android.content.res.*
import android.graphics.*
import android.graphics.drawable.*
import androidx.core.graphics.PathParser
import android.view.*
import android.widget.*
import android.animation.*
import android.view.animation.*
import de.robv.android.xposed.*

private const val TAG = "DrawableUtils"

fun loadModuleDrawable(
    pkgName: String,
    context: Context,
    iconName: String
): Drawable? {
    return try {
        val moduleContext = context.createPackageContext(
            pkgName,
            Context.CONTEXT_IGNORE_SECURITY
        )

        val resId = moduleContext.resources.getIdentifier(
            iconName,
            "drawable",
            pkgName
        )

        if (resId != 0) {
            moduleContext.getDrawable(resId)
        } else {
            XposedBridge.log("$TAG: icon not found: $iconName")
            null
        }
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: load icon failed icon=$iconName err=$t")
        null
    }
}
/*
setImageDrawable(
    com.sui.utils.loadModuleDrawable(
        PKG_MODULE,
        context,
        "res_name"
    )
)
*/

class HardcodedVectorDrawable(
    private val viewportWidth: Float = 24f,
    private val viewportHeight: Float = 24f,
    nodes: List<Node>
) : Drawable() {

    sealed interface Node

    data class Group(
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val rotation: Float = 0f,
        val pivotX: Float = 0f,
        val pivotY: Float = 0f,
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val children: List<Node>
    ) : Node

    data class PathNode(
        val pathData: String,
        val fillColor: Int,
        val fillAlpha: Float = 1f,
        val fillType: Path.FillType = Path.FillType.WINDING
    ) : Node

    private data class DrawItem(
        val path: Path,
        val fillColor: Int,
        val fillAlpha: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val drawItems: List<DrawItem>
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    init {
        require(viewportWidth > 0f) {
            "viewportWidth must be greater than 0"
        }
        require(viewportHeight > 0f) {
            "viewportHeight must be greater than 0"
        }

        val output = ArrayList<DrawItem>()
        val identity = Matrix()

        for (node in nodes) {
            collectNode(
                node = node,
                parentMatrix = identity,
                output = output
            )
        }

        drawItems = output
    }

    private fun collectNode(
        node: Node,
        parentMatrix: Matrix,
        output: MutableList<DrawItem>
    ) {
        when (node) {
            is Group -> {
                val localMatrix = createGroupMatrix(node)

                val currentMatrix = Matrix(parentMatrix).apply {
                    preConcat(localMatrix)
                }

                for (child in node.children) {
                    collectNode(
                        node = child,
                        parentMatrix = currentMatrix,
                        output = output
                    )
                }
            }

            is PathNode -> {
                val sourcePath = requireNotNull(
                    PathParser.createPathFromPathData(node.pathData)
                ) {
                    "Invalid pathData: ${node.pathData}"
                }

                sourcePath.fillType = node.fillType

                val transformedPath = Path()
                sourcePath.transform(parentMatrix, transformedPath)
                transformedPath.fillType = node.fillType

                output += DrawItem(
                    path = transformedPath,
                    fillColor = node.fillColor,
                    fillAlpha = node.fillAlpha.coerceIn(0f, 1f)
                )
            }
        }
    }

    private fun createGroupMatrix(group: Group): Matrix {
        return Matrix().apply {
            postTranslate(
                -group.pivotX,
                -group.pivotY
            )

            postScale(
                group.scaleX,
                group.scaleY
            )

            postRotate(group.rotation)

            postTranslate(
                group.translateX + group.pivotX,
                group.translateY + group.pivotY
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || drawItems.isEmpty()) return

        val scale = minOf(
            b.width().toFloat() / viewportWidth,
            b.height().toFloat() / viewportHeight
        )

        val drawWidth = viewportWidth * scale
        val drawHeight = viewportHeight * scale

        val offsetX =
            b.left + (b.width().toFloat() - drawWidth) / 2f

        val offsetY =
            b.top + (b.height().toFloat() - drawHeight) / 2f

        val saveCount = canvas.save()

        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        for (item in drawItems) {
            paint.color = item.fillColor
            paint.alpha = (
                drawableAlpha * item.fillAlpha
            ).toInt().coerceIn(0, 255)
            paint.colorFilter = drawableColorFilter

            canvas.drawPath(item.path, paint)
        }

        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        val newAlpha = alpha.coerceIn(0, 255)
        if (drawableAlpha == newAlpha) return

        drawableAlpha = newAlpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (drawableColorFilter === colorFilter) return

        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
/*
setImageDrawable(
    HardcodedVectorDrawable(
        viewportWidth = 512f,
        viewportHeight = 512f,
        nodes = listOf(
            HardcodedVectorDrawable.Group(
                scaleX = 0.1f,
                scaleY = -0.1f,
                translateY = 512f,
                children = listOf(
                    HardcodedVectorDrawable.PathNode(
                        fillColor = Color.WHITE,
                        pathData = "pathData"
                    ),
                    HardcodedVectorDrawable.PathNode(
                        fillColor = Color.WHITE,
                        pathData = "pathData"
                    )
                )
            ),
            HardcodedVectorDrawable.Group(
                scaleX = 12f,
                scaleY = 12f,
                translateX = 100f,
                translateY = 200f,
                children = listOf(
                    HardcodedVectorDrawable.PathNode(
                        fillColor = Color.WHITE,
                        pathData = 
                            """
                            
                                """
                    )
                )
            )
        )
    )
)
*/
