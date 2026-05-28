package com.screenwatch

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.*

class RegionSelectorActivity : Activity() {

    private lateinit var canvas: SelectionView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen, no status bar, no title
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        canvas = SelectionView(this)
        setContentView(canvas)
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    inner class SelectionView(ctx: Activity) : View(ctx) {

        private var startX = 0f
        private var startY = 0f
        private var curX = 0f
        private var curY = 0f
        private var dragging = false
        private var confirmed = false

        // Semi-transparent dim overlay
        private val dimPaint = Paint().apply {
            color = Color.argb(160, 0, 0, 0)
        }
        // Selection fill (clear-ish tint)
        private val selFillPaint = Paint().apply {
            color = Color.argb(30, 100, 180, 255)
        }
        // Selection border
        private val selBorderPaint = Paint().apply {
            color = Color.argb(230, 70, 150, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        // Corner handles
        private val cornerPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        // Instruction text
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 52f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        private val subTextPaint = Paint().apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = 34f
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        // Dimension label
        private val dimLabelPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }

        init {
            // Need software layer for proper PorterDuff operations
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // Full dim
            c.drawRect(0f, 0f, w, h, dimPaint)

            if (!dragging && !confirmed) {
                // Instructions
                c.drawText("Drag to select region", w / 2f, h / 2f - 30f, textPaint)
                c.drawText("Release to confirm • Back to cancel", w / 2f, h / 2f + 30f, subTextPaint)
                return
            }

            val left   = minOf(startX, curX)
            val top    = minOf(startY, curY)
            val right  = maxOf(startX, curX)
            val bottom = maxOf(startY, curY)
            val selRect = RectF(left, top, right, bottom)

            // Clear dim inside selection
            val clearPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            c.drawRect(selRect, clearPaint)
            // Light fill
            c.drawRect(selRect, selFillPaint)
            // Border
            c.drawRect(selRect, selBorderPaint)

            // Corner handles
            val hs = 18f
            listOf(
                RectF(left - hs, top - hs, left + hs, top + hs),
                RectF(right - hs, top - hs, right + hs, top + hs),
                RectF(left - hs, bottom - hs, left + hs, bottom + hs),
                RectF(right - hs, bottom - hs, right + hs, bottom + hs),
            ).forEach { c.drawOval(it, cornerPaint) }

            // Dimension label
            val selW = (right - left).toInt()
            val selH = (bottom - top).toInt()
            val labelY = if (top > 60f) top - 18f else bottom + 50f
            c.drawText("${selW} × ${selH} px", (left + right) / 2f, labelY, dimLabelPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    curX = event.x;   curY = event.y
                    dragging = true;  confirmed = false
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = event.x; curY = event.y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    curX = event.x; curY = event.y
                    dragging = false; confirmed = true
                    invalidate()
                    finishWithSelection()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun finishWithSelection() {
            val x = minOf(startX, curX).toInt()
            val y = minOf(startY, curY).toInt()
            val w = Math.abs(curX - startX).toInt()
            val h = Math.abs(curY - startY).toInt()

            if (w < 20 || h < 20) {
                // Too small – reset and let user try again
                dragging = false; confirmed = false
                startX = 0f; startY = 0f; curX = 0f; curY = 0f
                invalidate()
                return
            }

            setResult(RESULT_OK, Intent().apply {
                putExtra("x", x); putExtra("y", y)
                putExtra("width", w); putExtra("height", h)
            })
            finish()
        }
    }
}
