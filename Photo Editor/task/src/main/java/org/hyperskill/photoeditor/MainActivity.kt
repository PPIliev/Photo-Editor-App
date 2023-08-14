package org.hyperskill.photoeditor

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.graphics.drawable.toBitmap
import androidx.core.math.MathUtils.clamp
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {

    private lateinit var iv_currentImage: ImageView
    private lateinit var b_gallery: Button
    private lateinit var b_save: Button
    private lateinit var brightSlider: Slider
    private lateinit var contrastSlider: Slider
    private lateinit var saturationSlider: Slider
    private lateinit var gammaSlider: Slider
    private lateinit var dBitmap: Bitmap
    private lateinit var defaultBitmap: Bitmap // Store the default bitmap here
    private val REQUEST_CODE = 0
    private val STORAGE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    private var lastJob: Job? = null


    private val activityResultLauncher =
            registerForActivityResult(StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val photoUri = result.data?.data ?: return@registerForActivityResult
                    iv_currentImage.setImageURI(photoUri)

                    defaultBitmap = iv_currentImage.drawable.toBitmap()
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        defaultBitmap = createBitmap()
        dBitmap = defaultBitmap.copy(defaultBitmap.config, true) // Create a copy of the default bitmap

        // Set the default bitmap to the ImageView
        iv_currentImage.setImageBitmap(dBitmap)

        b_gallery.setOnClickListener {
            val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            activityResultLauncher.launch(galleryIntent)
        }


        brightSlider.addOnChangeListener { slider, value, fromUser ->
            applyFilters()
        }

        contrastSlider.addOnChangeListener { slider, value, fromUser ->
            applyFilters()
        }

        saturationSlider.addOnChangeListener { slider, value, fromUser ->
            applyFilters()
        }

        gammaSlider.addOnChangeListener { slider, value, fromUser ->
            applyFilters()
        }


        b_save.setOnClickListener {
            if (hasPermission(STORAGE_PERMISSION)) {
                savePhotoToExternalStorage(iv_currentImage.drawable.toBitmap())
            } else {
                ActivityCompat.requestPermissions(this,
                        arrayOf(STORAGE_PERMISSION), REQUEST_CODE)

            }

        }


    }

    private fun bindViews() {
        iv_currentImage = findViewById<ImageView>(R.id.ivPhoto)
        b_gallery = findViewById<Button>(R.id.btnGallery)
        brightSlider = findViewById<Slider>(R.id.slBrightness)
        b_save = findViewById<Button>(R.id.btnSave)
        contrastSlider = findViewById<Slider>(R.id.slContrast)
        saturationSlider = findViewById<Slider>(R.id.slSaturation)
        gammaSlider = findViewById<Slider>(R.id.slGamma)

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted, save the photo now
            savePhotoToExternalStorage(iv_currentImage.drawable.toBitmap())
        } else {
            // Permission denied, show a message to the user
            Toast.makeText(this, "Permission denied. Cannot save the photo.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun hasPermission(manifestPermission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.checkSelfPermission(manifestPermission) == PackageManager.PERMISSION_GRANTED
        } else {
            PermissionChecker.checkSelfPermission(this, manifestPermission) == PermissionChecker.PERMISSION_GRANTED
        }
    }


    private fun savePhotoToExternalStorage(bitmap: Bitmap) {
        val contentResolver: ContentResolver = applicationContext.contentResolver
        val imageCollectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val imageDetails = ContentValues().apply {
//            put(MediaStore.Images.Media.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpeg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
        }

        val imageUri = contentResolver.insert(imageCollectionUri, imageDetails)

        imageUri?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                outputStream.close()
            }
        }
    }



    private fun applyFilters() {
        lastJob?.cancel()

        lastJob = GlobalScope.launch(Dispatchers.Default) {
            println("applyFilters " + "job making calculations running on thread ${Thread.currentThread().name}")

            val bitmap = defaultBitmap.copy(defaultBitmap.config, true)

            val brightnessValue = brightSlider.value.toInt()
            val contrastValue = contrastSlider.value.toDouble()
            val saturationValue = saturationSlider.value.toDouble()
            val gammaValue = gammaSlider.value.toDouble()

            val brightness = async { calculateBrightnessFilter(bitmap, brightnessValue) }
            val contrast = async { calculateContrastFilter(brightness.await(), contrastValue, calculateAverageValues(brightness.await())) }
            val saturation = async { calculateSaturationFilter(contrast.await(), saturationValue) }

            val finalBitmap = calculateGammaFilter(saturation.await(), gammaValue)

            ensureActive()

            runOnUiThread {
                println("applyFilters " + "job updating view running on thread ${Thread.currentThread().name}")
                iv_currentImage.setImageBitmap(finalBitmap)
            }
        }
    }


    private fun calculateAverageValues(bitmap: Bitmap): Int {
        var total = 0L

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val sliderValue = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel))
                total += sliderValue
            }
        }

        return (total / (bitmap.width * bitmap.height * 3)).toInt()
    }


    private fun calculateContrastFilter(bitmap: Bitmap, contrastValue: Double, avgBrightness: Int): Bitmap {
        // Apply contrast filter
        val filteredBitmap = bitmap.copy(bitmap.config, true) // Create a fresh copy of the bitmap

        val alpha = (255 + contrastValue) / (255 - contrastValue)

        for (y in 0 until filteredBitmap.height) {
            for (x in 0 until filteredBitmap.width) {
                val pixel = filteredBitmap.getPixel(x, y)

                // Extract RGB components
                val originalR = Color.red(pixel)
                val originalG = Color.green(pixel)
                val originalB = Color.blue(pixel)

                // Calculate new RGB values with contrast adjustment
                val newR = clamp(((alpha * (originalR - avgBrightness)) + avgBrightness).toInt(), 0, 255)
                val newG = clamp(((alpha * (originalG - avgBrightness)) + avgBrightness).toInt(), 0, 255)
                val newB = clamp(((alpha * (originalB - avgBrightness)) + avgBrightness).toInt(), 0, 255)

                // Set the new pixel color to the filtered bitmap
                val newPixel = Color.rgb(newR, newG, newB)
                filteredBitmap.setPixel(x, y, newPixel)
            }
        }

        return filteredBitmap
    }




    private fun calculateBrightnessFilter(bitmap: Bitmap, brightnessValue: Int): Bitmap {
        // Apply brightness filter
        dBitmap = bitmap.copy(bitmap.config, true) // Create a fresh copy of the bitmap

        for (y in 0 until dBitmap.height) {
            for (x in 0 until dBitmap.width) {
                val pixel = dBitmap.getPixel(x, y)

                // Extract RGB components
                val originalR = Color.red(pixel)
                val originalG = Color.green(pixel)
                val originalB = Color.blue(pixel)

                // Calculate new RGB values with brightness adjustment
                val newR = clamp(originalR + brightnessValue, 0, 255)
                val newG = clamp(originalG + brightnessValue, 0, 255)
                val newB = clamp(originalB + brightnessValue, 0, 255)

                // Set the new pixel color to the filtered bitmap
                val newPixel = Color.rgb(newR, newG, newB)
                dBitmap.setPixel(x, y, newPixel)
            }
        }

        return dBitmap
    }


    private fun calculateSaturationFilter(bitmap: Bitmap, saturationValue: Double): Bitmap {
        // Apply contrast filter
        val filteredBitmap = bitmap.copy(bitmap.config, true) // Create a fresh copy of the bitmap

        val alpha = (255.0 + saturationValue) / (255.0 - saturationValue)

        for (y in 0 until filteredBitmap.height) {
            for (x in 0 until filteredBitmap.width) {
                val pixel = filteredBitmap.getPixel(x, y)

                // Extract RGB components
                val originalR = Color.red(pixel)
                val originalG = Color.green(pixel)
                val originalB = Color.blue(pixel)

                // Calculate new RGB values with contrast adjustment
                val avgRgb = (originalR + originalB + originalG) / 3.0
                val newR = clamp(((alpha * (originalR - avgRgb)) + avgRgb).toInt(), 0, 255)
                val newG = clamp(((alpha * (originalG - avgRgb)) + avgRgb).toInt(), 0, 255)
                val newB = clamp(((alpha * (originalB - avgRgb)) + avgRgb).toInt(), 0, 255)

                // Set the new pixel color to the filtered bitmap
                val newPixel = Color.rgb(clamp(newR, 0, 255), clamp(newG, 0, 255), clamp(newB, 0, 255))
                filteredBitmap.setPixel(x, y, newPixel)
            }
        }

        return filteredBitmap
    }



    private fun calculateGammaFilter(bitmap: Bitmap, gammaValue: Double): Bitmap {
        // Apply contrast filter
        val filteredBitmap = bitmap.copy(bitmap.config, true) // Create a fresh copy of the bitmap

        for (y in 0 until filteredBitmap.height) {
            for (x in 0 until filteredBitmap.width) {
                val pixel = filteredBitmap.getPixel(x, y)

                // Extract RGB components
                val originalR = Color.red(pixel)
                val originalG = Color.green(pixel)
                val originalB = Color.blue(pixel)

                // Calculate new RGB values with contrast adjustment
                val newR = (255 * Math.pow(originalR / 255.0, gammaValue)).toInt()
                val newG = (255 * Math.pow(originalG / 255.0, gammaValue)).toInt()
                val newB = (255 * Math.pow(originalB / 255.0, gammaValue)).toInt()


                // Set the new pixel color to the filtered bitmap
                val newPixel = Color.rgb(clamp(newR, 0, 255), clamp(newG, 0, 255), clamp(newB, 0, 255))

                filteredBitmap.setPixel(x, y, newPixel)
            }
        }

        return filteredBitmap
    }




    // do not change this function
    fun createBitmap(): Bitmap {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height)
        // get pixel array from source

        var R: Int
        var G: Int
        var B: Int
        var index: Int

        for (y in 0 until height) {
            for (x in 0 until width) {
                // get current index in 2D-matrix
                index = y * width + x
                // get color
                R = x % 100 + 40
                G = y % 100 + 80
                B = (x+y) % 100 + 120

                pixels[index] = Color.rgb(R,G,B)

            }
        }
        // output bitmap
        val bitmapOut = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmapOut.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmapOut
    }
}