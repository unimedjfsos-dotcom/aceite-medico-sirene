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

  /// Prepares Android for urgent full-screen medical alerts.
  /// On Android 14+, opens the official system permission page only when needed.
  /// Returns true when full-screen alert access is already available.
  static Future<bool> prepararAlertaUrgente() async {
    return await _channel.invokeMethod<bool>('prepareUrgentAlert') ?? false;
  }

  static Future<bool> podeAbrirTelaCheia() async {
    return await _channel.invokeMethod<bool>('canUseFullScreenIntent') ?? false;
  }
}
