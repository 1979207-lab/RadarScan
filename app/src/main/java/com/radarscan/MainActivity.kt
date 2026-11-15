package com.radarscan

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

data class WifiEntry(val ssid:String,val bssid:String,val rssi:Int,val freq:Int,val channel:Int,val caps:String)
class RadarViewModel:ViewModel(){
    var networks by mutableStateOf<List<WifiEntry>>(emptyList()); private set
    var status by mutableStateOf("Нажмите «Сканировать»"); private set
    var busy by mutableStateOf(false); private set
    @Suppress("DEPRECATION")
    fun scan(wifi:WifiManager){
        if(busy)return
        viewModelScope.launch{
            busy=true;status="Запрашиваю сканирование Wi-Fi…"
            try{
                wifi.startScan();delay(3500)
                networks=wifi.scanResults.orEmpty().map{
                    val ch=when(it.frequency){2484->14;in 2412..2472->(it.frequency-2407)/5;in 5000..5900->(it.frequency-5000)/5;else->-1}
                    WifiEntry(it.SSID.ifBlank{"<скрытая сеть>"},it.BSSID?:"—",it.level,it.frequency,ch,it.capabilities?:"")
                }.distinctBy{it.bssid}.sortedByDescending{it.rssi}
                status="Найдено Wi-Fi сетей: ${networks.size}"
            }catch(_:SecurityException){status="Нет разрешения. Разрешите доступ к местоположению."}
            busy=false
        }
    }
}
class MainActivity:ComponentActivity(){
    private val ask=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val p= mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if(Build.VERSION.SDK_INT>=33)p+=Manifest.permission.NEARBY_WIFI_DEVICES
        if(p.any{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED})ask.launch(p.toTypedArray())
        setContent{RadarScanApp()}
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RadarScanApp(vm:RadarViewModel=viewModel()){
    val context=androidx.compose.ui.platform.LocalContext.current
    val wifi=remember{context.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager}
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF55D6A5),background=Color(0xFF08111C),surface=Color(0xFF132234))){
        Scaffold(topBar={TopAppBar(title={Text("RadarScan")})}){padding->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)){
                Radar(vm.networks,Modifier.fillMaxWidth().height(280.dp))
                Text(vm.status)
                Button(onClick={vm.scan(wifi)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){
                    Text(if(vm.busy)"Сканирование…" else "Сканировать")
                }
                LazyColumn{items(vm.networks){n->
                    ListItem(headlineContent={Text(n.ssid)},supportingContent={
                        Text("${n.bssid} · ${n.rssi} dBm · канал ${n.channel} · ${n.freq} MHz · ${n.caps}")
                    })
                }}
            }
        }
    }
}
@Composable fun Radar(networks:List<WifiEntry>,modifier:Modifier=Modifier){
    Canvas(modifier.padding(8.dp)){
        val c=Offset(size.width/2,size.height/2);val r=min(size.width,size.height)*.44f
        (1..4).forEach{drawCircle(Color(0xFF24435A),r*it/4f,c,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))}
        drawLine(Color(0xFF24435A),Offset(c.x-r,c.y),Offset(c.x+r,c.y),1.dp.toPx())
        drawLine(Color(0xFF24435A),Offset(c.x,c.y-r),Offset(c.x,c.y+r),1.dp.toPx())
        networks.take(60).forEach{n->
            val a=((n.bssid.hashCode().toLong() and 0x7fffffff)%360).toDouble()
            val d=(-n.rssi).coerceIn(30,100)/100f*r
            val p=Offset(c.x+cos(Math.toRadians(a)).toFloat()*d,c.y+sin(Math.toRadians(a)).toFloat()*d)
            val color=when{n.rssi>-60->Color(0xFF43D17D);n.rssi>-75->Color(0xFFFFC247);else->Color(0xFFFF5B62)}
            drawCircle(color,5.dp.toPx(),p)
        }
    }
}
