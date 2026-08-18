# Aceite Médico Sirene

Plugin Android para o projeto Aceite Médico / SOS UNIMED.

## Funções

- inicia uma sirene nativa em foreground service;
- mantém a reprodução com a tela bloqueada;
- inicia a sirene quando o OneSignal entrega uma notificação no Android;
- permite parar a sirene pelo botão CIENTE via MethodChannel.

## FlutterFlow

Adicionar como dependência Git:

```yaml
aceite_medico_sirene:
  git:
    url: https://github.com/SEU_USUARIO/aceite-medico-sirene.git
```

No Custom Action:

```dart
import 'package:aceite_medico_sirene/aceite_medico_sirene.dart';

Future pararSireneNativa() async {
  await AceiteMedicoSirene.parar();
}
```

Observação: notificações de push em Android não são processadas se o usuário fizer "Forçar parada" do aplicativo. O comportamento também pode variar conforme otimizações de bateria do fabricante.
