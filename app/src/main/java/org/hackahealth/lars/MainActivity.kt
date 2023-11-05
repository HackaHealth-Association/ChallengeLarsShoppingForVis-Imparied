package org.hackahealth.lars

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.OnDoubleTapListener
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.google.common.io.BaseEncoding
import com.opencsv.CSVReader
import org.hackahealth.lars.databinding.ActivityMainBinding
import java.io.InputStreamReader
import java.util.Locale
import android.os.Handler
import android.os.Looper


class Article {
    var nfcId: String = ""
    var id: Int = 0
    var name: String = ""
    var category: String = ""
    var entry: Int = 0
    var aisle: Int = 0
    var position: Int = 0
}

class MainActivity : AppCompatActivity(), OnInitListener, GestureDetector.OnGestureListener,
    OnDoubleTapListener {
    // Reference to the activity_main.xml layout elements
    private lateinit var binding: ActivityMainBinding
    // Reference to the NFC service
    private lateinit var adapter: NfcAdapter
    // Reference the Text To Speech service
    private lateinit var tts: TextToSpeech
    // Hashmap indexed by NFC IDs and pointing to article entries
    private lateinit var listOfShopItems: MutableMap<String, Article>
    // Hard-coded list of items to buy
    private lateinit var shoppingList: Array<String>
    private var currentObjectIndex: Int = 0
    private var currentCategory: String = ""
    // Gesture detection
    private lateinit var detector: GestureDetectorCompat
    // Last spoken sentence for repeat
    private var lastSentence: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        // On creation, inflate the activity_main.xml layout
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Grab a reference to the NFC service
        val nfcManager = getSystemService(Context.NFC_SERVICE) as NfcManager
        adapter = nfcManager.defaultAdapter

        // Initialize the text to speech module
        tts = TextToSpeech(this, this)
        tts.setSpeechRate(1.0f)

        // Parse the products database in CSV
        readProductsDatabase()

        // Hardcode a shopping list
        shoppingList = arrayOf<String>("Brown Choco", "Arduino Uno", "Battery 9v")

        // Gestures detection
        detector = GestureDetectorCompat(this, this)
        detector.setOnDoubleTapListener(this)
    }

    fun readProductsDatabase() {
        val reader = CSVReader(InputStreamReader(assets.open("products.csv")))
        // Skip the header
        val header = reader.readNext()
        var line: Array<String>?

        listOfShopItems = mutableMapOf<String, Article>()

        // NFC-Tag-Nr,Art.-Nr.,Art.-Bez.,PositionimLayout,LB-Name
        Log.i("MainActivity","header= "+header)
        while (reader.readNext().also { line = it } != null) {
            var article = Article()
            article.nfcId = line!![1]
            article.id = line!![0].toInt()
            article.name = line!![2]
            article.category = line!![4]
            article.entry = 0
            article.aisle = 0
            article.position = line!![3].toInt()

            listOfShopItems.put(article.nfcId, article)
        }
    }

    // TTS Initialization callback
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "This Language is not supported")
            } else {
                speak("Hi. We are looking for: " + shoppingList.get(currentObjectIndex) )
            }
        } else {
            Log.e("TTS", "Initialize Failed")
        }
    }

    override fun onResume() {
        super.onResume()

        // When the app is entered, enabled reception of NFC events
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        adapter.enableForegroundDispatch(this, nfcPendingIntent, null, null)

        try {
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
            adapter.enableForegroundDispatch(this, nfcPendingIntent, null, null)
        } catch (ex: IllegalStateException) {
            Log.e("MainActivity", "Error enabling NFC foreground dispatch", ex)
        }
    }

    override fun onPause() {
        // When the app is paused, disable reception of NFC events
        try {
            adapter.disableForegroundDispatch(this)
        } catch (ex: IllegalStateException) {
            Log.e("MainActivity", "Error disabling NFC foreground dispatch", ex)
        }

        super.onPause()
    }

    // NFC events are received as Intents
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Only consider NFC tags discovery events
        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) {
            return
        }

        // Iterate over messages broadcast by the NFC tag
        val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return
        for (rawMsg in rawMsgs) {
            val msg = rawMsg as NdefMessage

            // Iterate over records of the message
            for (record in msg.records) {
                // Only keep URLs records
                if (record.type[0].compareTo(0x55) != 0) {
                    continue
                }

                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)!!
                val tagId = BaseEncoding.base16().encode(tag.id)
                val tagUri = record.toUri().toString()

                val prefix = "https://nfc.imagotag.com/"
                if (!tagUri.startsWith(prefix))
                    continue

                onTagTapped(tagId, tagUri.replace(prefix, ""))
                return
            }
        }
    }

    private fun speak(str: String) {
        lastSentence = str
        tts.speak(str, TextToSpeech.QUEUE_FLUSH, null)
    }

    // Simple callback only called on price tags
    private fun onTagTapped(tagId: String, tagUri: String) {
        var currentProductNFCID: String = tagUri
        val currentProductScanned = listOfShopItems[currentProductNFCID] ?: return

        if (currentObjectIndex >= shoppingList.size) {
            speak("Shopping list empty");
            return
        }
        // For all items in the shopping list, check if
        var targetArticleName = shoppingList.get(currentObjectIndex)
        // for (targetArticleName in shoppingList) {
        if (currentProductScanned.name == targetArticleName) {
            speak(currentProductScanned.name + " found. Double-tap to confirm.")
            return
        }

        // Find the target article
        var targetArticle: Article? = null
        val e = listOfShopItems.keys.iterator()
        while (e.hasNext()) {
            val key = e.next()
            val possibleItemInShop = listOfShopItems[key] ?: return
            if (possibleItemInShop.name == targetArticleName)
                targetArticle = possibleItemInShop
        }

        if (targetArticle == null) {
            Log.e("MainActivity", "Couldn't find target article")
            return
        }

        if (targetArticle.category == currentProductScanned.category) {
            var direction = ""
            var item = "items"
            if (targetArticle.id-currentProductScanned.id == 1 || targetArticle.id-currentProductScanned.id == -1)
                item = "item"

            if (targetArticle.id-currentProductScanned.id < 0) {
                direction = "" + (-(targetArticle.id-currentProductScanned.id)) + " " + item + " before"
            } else {
                direction = "" + (targetArticle.id - currentProductScanned.id) + " " + item + " further"
            }

            if (currentCategory != currentProductScanned.category) {
                currentCategory = currentProductScanned.category
                speak(currentProductScanned.category + " category found. " + direction)
            } else {
                speak(direction)
            }
        } else {
            currentCategory = ""
            speak("Wrong category. Go to  " + targetArticle.category + " category")
        }
    }

    // Validate that a product has been found
    override fun onDoubleTap(event: MotionEvent): Boolean {
        binding.view.setBackgroundColor(Color.parseColor("#00FF00"))
        val handler = Handler(Looper.getMainLooper())

        val myFunction = Runnable {
            binding.view.setBackgroundColor(Color.parseColor("#F44336"))
        }
        handler.postDelayed(myFunction, 2000)

        if (currentObjectIndex + 1 >= shoppingList.size) {
            speak("You are done now")
        } else {
            speak(shoppingList.get(currentObjectIndex) + " removed from your list. Next is " + shoppingList.get(currentObjectIndex+1))
            currentObjectIndex = currentObjectIndex+1
        }

        // TODO: remove from the shopping list
        return true
    }

    // Swipe gesture handler
    override fun onFling(
        event1: MotionEvent, event2: MotionEvent,
        velocityX: Float, velocityY: Float
    ): Boolean {
        // Repeat the last sentence pronounced
        speak(lastSentence)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Actions on screen clicks
        return if (detector!!.onTouchEvent(event)) {
            true
        } else super.onTouchEvent(event)
    }

    // Required override of callbacks for touch events
    override fun onDown(event: MotionEvent): Boolean {
        return true
    }
    override fun onScroll(
        event1: MotionEvent, event2: MotionEvent, distanceX: Float,
        distanceY: Float
    ): Boolean {
        return true
    }
    override fun onShowPress(event: MotionEvent) {}
    override fun onSingleTapUp(event: MotionEvent): Boolean {
        // Confirming current selection
        return true
    }
    override fun onDoubleTapEvent(event: MotionEvent): Boolean {
        return true
    }
    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        return true
    }
    override fun onLongPress(event: MotionEvent) {}
}