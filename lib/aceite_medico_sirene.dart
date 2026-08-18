import 'package:flutter/services.dart';

class AceiteMedicoSirene {
  static const MethodChannel _channel =
      MethodChannel('aceite_medico_sirene');

  static Future<void> iniciar() async {
    await _channel.invokeMethod<void>('startSiren');
  }

  static Future<void> parar() async {
    await _channel.invokeMethod<void>('stopSiren');
  }
}
