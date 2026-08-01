# YSM Mapping API

> [!CAUTION]
> 現在開発中のため破壊的変更をする可能性があります。

YSM Mapping APIは、導入済みYes Steve Model（YSM）の難読化されたclass、method、fieldを構造解析し、他のmodへ型付きsymbolとして提供する前提modです。

## 対応環境

- Java 21
- Minecraft 1.21.1
- FabricまたはNeoForge
- Yes Steve Model 2.6.0以上

解析はローカルで完結し、ネット照会やYSMの自動ダウンロードは行いません。一意に検証できないsymbolは返さず、最後に正常解析したYSMだけをキャッシュします。

## 利用方法

利用modはJARへ`META-INF/ysm-mapping-api/requests-v1.json`を格納します。consumer mod IDはresourceを所有するmod containerから取得されます。

```json
{
  "schemaVersion": 1,
  "symbols": [
    {
      "key": "ysm.network.packet.1.class",
      "kind": "CLASS",
      "required": true,
      "sourceAlias": {
        "common": {
          "owner": "net/okitsu/example/ysmref/PacketOne"
        }
      }
    },
    {
      "key": "ysm.client.send.method",
      "kind": "METHOD",
      "required": true,
      "sourceAlias": {
        "common": {
          "owner": "net/okitsu/example/ysmref/ClientSender",
          "name": "send",
          "descriptor": "(Ljava/nio/ByteBuffer;)V"
        },
        "neoforge": {
          "name": "sendNeoForge"
        }
      }
    },
    {
      "key": "ysm.client.model_manager.start_sync.method",
      "kind": "METHOD",
      "required": true
    }
  ],
  "mixinRequirements": {
    "net.example.mixin.YsmPacketMixin": [
      "ysm.network.packet.1.class",
      "ysm.client.send.method"
    ]
  }
}
```

初期化後に`YsmMappingApi.resolve(consumerModId, symbolKeys)`を呼び、返された`MappingSnapshot`から必要なsymbolを取得します。組み込みsemantic keyは`YsmSymbols.registry()`で参照できます。独自keyは`consumerClass`、`consumerMethod`、`consumerField`でconsumerごとに定義してください。

Mixinでsymbolを使用する場合は、Mixin configへpluginとruntime refmap wrapperを設定し、対象symbolに`sourceAlias`を指定します。`common`に完全なaliasを記述し、`fabric`または`neoforge`では指定したフィールドだけを上書きできます。上の例ではNeoForgeの`name`だけが`sendNeoForge`になり、`owner`と`descriptor`は`common`から継承されます。

`YsmMappingApi.resolve`の結果をReflectionやMethodHandleなどから使うだけのcurated keyには`sourceAlias`は不要です。上の`ysm.client.model_manager.start_sync.method`のように`sourceAlias`を省略し、`mixinRequirements`にも含めません。

```json
{
  "package": "net.example.mixin",
  "plugin": "net.okitsu.ysmmapping.internal.mixin.YsmMappingMixinPlugin",
  "refmapWrapper": "YsmReferenceMapper"
}
```

## キャッシュ

```text
config/ysm_mapping_api/
├─ settings.json
├─ mappings.json
└─ mappings.lock
```

同じYSMと要求内容では`mappings.json`を再利用します。YSMまたは定義が変わった場合は、必要な解析を完了してからファイルを置き換えます。`mappings.lock`は解析中の排他制御にだけ使用します。

既定の`SAFE_ONLY`では、一意に検証できた`STRUCTURAL`結果だけを公開します。必須symbolが解決できないconsumerは安全に停止します。

## モジュール

- `api-core`: 共通の公開型
- `analysis-core`: bytecodeと構造の解析
- `api`、`common`: Minecraft向けAPIとruntime
- `fabric`、`neoforge`: loader統合
- `mapping-tool`: ローカルfixture検証用CLI

## 開発

```powershell
.\gradlew.bat clean build verifyDistributions
```

YSMのJAR、runtime名、解析graph、ネイティブライブラリ、逆コンパイル結果はGitや配布物へ含めません。
