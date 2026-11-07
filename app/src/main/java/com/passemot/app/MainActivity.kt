package com.passemot.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Passe-Mot : premier test.
 * Deux téléphones se trouvent sans internet et s'échangent des messages texte.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val SERVICE_ID = "com.passemot.app.v1"
        private const val CODE_PERMISSIONS = 42
    }

    // P2P_CLUSTER : chaque téléphone peut être connecté à plusieurs voisins (base du maillage)
    private val strategie = Strategy.P2P_CLUSTER
    private val monNom = "Tel-" + (1000..9999).random()
    private lateinit var client: ConnectionsClient

    private val voisins = mutableMapOf<String, String>()   // idVoisin -> nom
    private val noms = mutableMapOf<String, String>()      // idVoisin -> nom (avant connexion)
    private val demandesEnCours = mutableSetOf<String>()
    private var reseauActif = false

    private lateinit var statut: TextView
    private lateinit var journal: TextView
    private lateinit var defilement: ScrollView
    private lateinit var champMessage: EditText
    private lateinit var boutonDemarrer: Button
    private lateinit var boutonEnvoyer: Button
    private lateinit var boutonPing: Button

    private val rythme = Handler(Looper.getMainLooper())
    private var pingActif = false
    private var numeroPing = 0

    // ------------------------------------------------------------------
    // Écran (construit en code pour éviter les fichiers de mise en page)
    // ------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)
        construireEcran()
        ecrire("Mon nom : $monNom")
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    private fun construireEcran() {
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val titre = TextView(this).apply {
            text = "Passe-Mot (test)"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }

        statut = TextView(this).apply {
            text = "Réseau arrêté"
            setPadding(0, dp(8), 0, dp(8))
        }

        boutonDemarrer = Button(this).apply {
            text = "Démarrer le réseau"
            setOnClickListener { demarrerOuArreter() }
        }

        journal = TextView(this).apply { textSize = 14f }
        defilement = ScrollView(this).apply { addView(journal) }

        champMessage = EditText(this).apply { hint = "Écris un message" }

        boutonPing = Button(this).apply {
            text = "Test de portée (ping)"
            setOnClickListener { basculerPing() }
        }

        boutonEnvoyer = Button(this).apply {
            text = "Envoyer"
            isEnabled = false
            setOnClickListener { envoyer() }
        }

        racine.addView(titre)
        racine.addView(statut)
        racine.addView(boutonDemarrer)
        racine.addView(boutonPing)
        racine.addView(
            defilement,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        racine.addView(champMessage)
        racine.addView(boutonEnvoyer)
        setContentView(racine)
    }

    private fun ecrire(ligne: String) {
        val heure = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        journal.append("[$heure] $ligne\n")
        defilement.post { defilement.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun majStatut() {
        statut.text = if (!reseauActif) {
            "Réseau arrêté"
        } else {
            "Réseau actif : ${voisins.size} téléphone(s) connecté(s)"
        }
        boutonEnvoyer.isEnabled = voisins.isNotEmpty()
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------
    private fun permissionsNecessaires(): List<String> {
        val liste = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            liste.add(Manifest.permission.BLUETOOTH_SCAN)
            liste.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            liste.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            liste.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return liste
    }

    private fun demarrerOuArreter() {
        if (reseauActif) {
            arreter()
            return
        }
        val manquantes = permissionsNecessaires().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (manquantes.isEmpty()) {
            demarrer()
        } else {
            ActivityCompat.requestPermissions(this, manquantes.toTypedArray(), CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                demarrer()
            } else {
                ecrire("Permissions refusées : le réseau ne peut pas démarrer.")
            }
        }
    }

    // ------------------------------------------------------------------
    // Réseau : on se rend visible ET on cherche les autres en même temps
    // ------------------------------------------------------------------
    private fun demarrer() {
        val optionsVisible = AdvertisingOptions.Builder().setStrategy(strategie).build()
        client.startAdvertising(monNom, SERVICE_ID, cycleConnexion, optionsVisible)
            .addOnSuccessListener { ecrire("Je suis visible des autres téléphones") }
            .addOnFailureListener { ecrire("Erreur (visibilité) : ${it.message}") }

        val optionsRecherche = DiscoveryOptions.Builder().setStrategy(strategie).build()
        client.startDiscovery(SERVICE_ID, decouverte, optionsRecherche)
            .addOnSuccessListener { ecrire("Je cherche des téléphones autour...") }
            .addOnFailureListener { ecrire("Erreur (recherche) : ${it.message}") }

        reseauActif = true
        boutonDemarrer.text = "Arrêter le réseau"
        majStatut()
        rythme.postDelayed(boucleRecherche, 15000)
    }

    private fun arreter() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        rythme.removeCallbacksAndMessages(null)
        pingActif = false
        boutonPing.text = "Test de portée (ping)"
        voisins.clear()
        noms.clear()
        demandesEnCours.clear()
        reseauActif = false
        boutonDemarrer.text = "Démarrer le réseau"
        majStatut()
        ecrire("Réseau arrêté")
    }

    // Quand un autre téléphone est trouvé
    private val decouverte = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (voisins.containsKey(id) || demandesEnCours.contains(id)) return
            // Pour éviter que 2 téléphones se demandent en même temps,
            // seul celui dont le nom est "le plus grand" lance la demande.
            if (monNom > info.endpointName) {
                demandesEnCours.add(id)
                ecrire("Trouvé : ${info.endpointName}")
                client.requestConnection(monNom, id, cycleConnexion)
                    .addOnFailureListener {
                        demandesEnCours.remove(id)
                        ecrire("Échec de la demande : ${it.message}")
                    }
            }
        }

        override fun onEndpointLost(id: String) {
            demandesEnCours.remove(id)
        }
    }

    // Vie d'une connexion : demande, résultat, déconnexion
    private val cycleConnexion = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            noms[id] = info.endpointName
            ecrire("Connexion avec ${info.endpointName}...")
            client.acceptConnection(id, receptionMessages)
        }

        override fun onConnectionResult(id: String, resultat: ConnectionResolution) {
            demandesEnCours.remove(id)
            if (resultat.status.isSuccess) {
                voisins[id] = noms[id] ?: id
                ecrire("Connecté à ${voisins[id]}")
            } else {
                ecrire("Connexion échouée (code ${resultat.status.statusCode})")
            }
            majStatut()
        }

        override fun onDisconnected(id: String) {
            ecrire("${voisins[id] ?: id} s'est déconnecté (hors de portée ?)")
            voisins.remove(id)
            noms.remove(id)
            demandesEnCours.remove(id)
            majStatut()
            relancerRecherche()
        }
    }

    // Réception des messages
    private val receptionMessages = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val texte = String(payload.asBytes() ?: return, Charsets.UTF_8)
                ecrire("${voisins[id] ?: id} : $texte")
            }
        }

        override fun onPayloadTransferUpdate(id: String, maj: PayloadTransferUpdate) {
            // rien à faire pour de petits messages texte
        }
    }

    // Relance la recherche : sert à se reconnecter quand on revient à portée
    private fun relancerRecherche() {
        if (!reseauActif) return
        client.stopDiscovery()
        val options = DiscoveryOptions.Builder().setStrategy(strategie).build()
        client.startDiscovery(SERVICE_ID, decouverte, options)
            .addOnFailureListener { ecrire("Erreur (recherche) : ${it.message}") }
    }

    // Toutes les 15 s, si on n'a aucun voisin, on relance la recherche
    private val boucleRecherche = object : Runnable {
        override fun run() {
            if (!reseauActif) return
            if (voisins.isEmpty()) relancerRecherche()
            rythme.postDelayed(this, 15000)
        }
    }

    // Test de portée : un "ping" toutes les 2 secondes
    private val boucleDuPing = object : Runnable {
        override fun run() {
            if (!pingActif) return
            if (voisins.isNotEmpty()) {
                numeroPing++
                val texte = "ping #$numeroPing"
                client.sendPayload(voisins.keys.toList(), Payload.fromBytes(texte.toByteArray(Charsets.UTF_8)))
            }
            rythme.postDelayed(this, 2000)
        }
    }

    private fun basculerPing() {
        pingActif = !pingActif
        if (pingActif) {
            numeroPing = 0
            boutonPing.text = "Arrêter le test de portée"
            ecrire("Test de portée : un ping toutes les 2 s. Éloigne-toi et note où ils s'arrêtent.")
            rythme.post(boucleDuPing)
        } else {
            boutonPing.text = "Test de portée (ping)"
            rythme.removeCallbacks(boucleDuPing)
        }
    }

    private fun envoyer() {
        val texte = champMessage.text.toString().trim()
        if (texte.isEmpty() || voisins.isEmpty()) return
        client.sendPayload(voisins.keys.toList(), Payload.fromBytes(texte.toByteArray(Charsets.UTF_8)))
        ecrire("Moi : $texte")
        champMessage.setText("")
    }

    override fun onDestroy() {
        rythme.removeCallbacksAndMessages(null)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        super.onDestroy()
    }
}
