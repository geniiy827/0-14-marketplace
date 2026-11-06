# NOVA accounts (alpha15)

## Install on the server (Genisys 0.14)
1. Put `NovaAccounts.phar` into `plugins/` and restart the server.
2. The log should show `NOVA accounts: 0 accounts, key b32d6f7f...`.
3. Data: `plugins/NovaAccounts/accounts.db` (SQLite), settings and all texts: `plugins/NovaAccounts/config.yml`.
   `plugins/NovaAccounts/private.pem` is the server key. **Keep it.** If it is lost or changed, clients can no longer
   sign in until the APK is rebuilt with the new public key.
Nothing else is needed: the plugin answers on the game port (UDP), next to the game. It works with query on or off.

## Commands (op)
`/novaacc info <tag>`, `/novaacc ban <tag> [minutes|0] [reason]`, `/novaacc unban <tag>`,
`/novaacc logoutall <tag>`, `/novaacc list`, `/novaacc stats`

## For other plugins
```php
$acc = $this->getServer()->getPluginManager()->getPlugin("NovaAccounts");
$a = $acc->getAccount("Tag");            // id, tag, created, last_login, last_ip, banned, ban_until, ban_reason, data
$acc->ban("Tag", 60, "cheats"); $acc->unban("Tag"); $acc->logoutAll("Tag");
$acc->setData("Tag", "friends", ["A", "B"]); $acc->getData("Tag", "friends", []);
// new requests from the client (friends, etc.): the client sends {"op": "friends.list", "token": ...}
$acc->getService()->registerOp("friends.list", function(array $req, $account, $ip){
    if($account === null) return ["ok" => false, "err" => "session", "msg" => "Войди в аккаунт"];
    return ["ok" => true, "friends" => []];
});
```
Events (namespace `NovaAccounts\event`): `AccountRegisterEvent` (cancellable, setReason), `AccountCreatedEvent`,
`AccountLoginEvent` (cancellable, setReason), `AccountLogoutEvent`.

## Protocol
UDP to the game port. `FE FD 4E 01 00 + nonce8` is the hello; the answer carries the server time and the key fingerprint.
`FE FD 4E 01 01 + RSA-OAEP(AES key + HMAC key) + iv + AES-128-CBC(JSON) + HMAC-SHA256` is a request. Details are in
`server/NovaAccounts/src/NovaAccounts/Crypto.php`, and the client is `java/.../AccountClient.java`.
Security:
- Passwords are stored as bcrypt; session tokens as sha256.
- Every request carries a time and a nonce (no replays).
- Rate limits per IP and per account; an account is locked after 5 wrong passwords in a row.
- Login takes the same time whether or not the tag exists.

## Client
- The account button sits right of the storage button on the start screen (`nova://account`).
- The screen is the game's own options screen (native/menus.inc, NOVA_ACCOUNT).
- The session lives in `files/nova/account.properties`.
- Glyph U+E100 = nova_player (`images/font/glyph_E1.png`), for names later.
