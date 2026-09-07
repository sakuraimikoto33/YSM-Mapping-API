# Analysis core

クライアントへ組み込まれる`api-core`と`analysis-core`はJava 17互換です。
ローカルfixture検証用の`mapping-tool`はJava 21で動作します。

Minecraft ごとの profile は、既存の必須シンボルに加えて、追加解析グループを
`symbols` に全件記載することで明示的に有効化します。グループの部分指定や
未知のシンボルは拒否し、有効化していないグループの結果・診断は出力しません。
既存 profile の定義・fingerprint・digest は、共有 registry への追加だけでは変わりません。

Molang クエリの追加グループは公開登録名と実装関数の対応を解析し、最終関数と
必要な context accessor を返します。速度式の複製や context の新規生成は行いません。
ASM のデータフロー解析は既存 ASM と同じ 9.7.1 を使用し、曖昧な登録や未対応の
入力依存は未解決として扱います。
