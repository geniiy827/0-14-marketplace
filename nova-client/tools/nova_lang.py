"""NOVA strings for every pocket .lang (key=value<TAB>#). Applied by build.sh to assets_new/loc.
Rule (0.14 font): a string with Cyrillic/CJK switches the whole line to the unicode font, where Latin
letters are drawn too wide -> no Latin inside such strings, no '…' in Latin ones."""
import os, sys, re

K = ['options.vrHudAtTop', 'options.vrHudDrifts', 'options.vrRightStickPitchAssistSteppings', 'options.vrGazePitchBoost',
     'options.vrUsesRedFlashForHit', 'options.vrRightStickGazeAdjust', 'options.group.nova', 'options.group.nova.textures',
     'nova.opt.newtextures', 'nova.storage.title', 'nova.packs.title', 'nova.packs.on', 'nova.packs.del', 'nova.packs.none',
     'nova.packs.import', 'nova.packs.import.sub', 'nova.packs.import.file', 'nova.toast.imported', 'nova.toast.importfail',
     'nova.toast.crash']

T = {
 'en_US': ['Show paper doll', 'Debug screen (F3)', 'Paper doll size', 'FPS counter', 'Chunk borders', 'Auto sprint (forward button)',
           'NOVA', 'Textures', 'New textures (restarts)', 'Storage', 'Resource Packs', 'Enabled', 'Delete (on exit)',
           'No packs yet. Import one on the Import tab', 'Import a pack', 'A .mcres file from this device', 'Choose a .mcres file...',
           'Pack imported', 'Import failed', 'NOVA: the game crashed'],
 'ru_RU': ['Показать куклу', 'Отладка (F3)', 'Размер куклы', 'Счётчик FPS', 'Границы чанков', 'Автобег (кнопкой вперёд)',
           'NOVA', 'Текстуры', 'Новые текстуры (перезапуск)', 'Хранилище', 'Наборы ресурсов', 'Включён', 'Удалить (при выходе)',
           'Наборов нет. Импортируйте их на вкладке «Импорт»', 'Импорт набора', 'Файл набора с этого устройства', 'Выбрать файл .mcres',
           'Набор импортирован', 'Ошибка импорта', 'Игра вылетела: отчёт скопирован (вставь в чат) и в Загрузках'],
 'de_DE': ['Spielerfigur anzeigen', 'Debug-Bildschirm (F3)', 'Größe der Spielerfigur', 'FPS-Anzeige', 'Chunkgrenzen',
           'Automatisch sprinten (Vorwärtstaste)', 'NOVA', 'Texturen', 'Neue Texturen (Neustart)', 'Speicher', 'Ressourcenpakete',
           'Aktiviert', 'Löschen (beim Verlassen)', 'Noch keine Pakete. Importiere eins im Tab Import', 'Paket importieren',
           'Eine .mcres-Datei von diesem Gerät', 'Datei .mcres auswählen...', 'Paket importiert', 'Import fehlgeschlagen',
           'NOVA: Das Spiel ist abgestürzt'],
 'es_ES': ['Mostrar muñeco', 'Pantalla de depuración (F3)', 'Tamaño del muñeco', 'Contador de FPS', 'Bordes de chunks',
           'Correr automático (botón adelante)', 'NOVA', 'Texturas', 'Texturas nuevas (reinicia)', 'Almacén', 'Paquetes de recursos',
           'Activado', 'Eliminar (al salir)', 'No hay paquetes. Importa uno en la pestaña Importar', 'Importar paquete',
           'Un archivo .mcres de este dispositivo', 'Elegir archivo .mcres...', 'Paquete importado', 'Error al importar',
           'NOVA: el juego se cerró'],
 'fr_FR': ['Afficher la poupée', 'Écran de débogage (F3)', 'Taille de la poupée', 'Compteur de FPS', 'Bordures de chunks',
           'Sprint auto (bouton avancer)', 'NOVA', 'Textures', 'Nouvelles textures (redémarre)', 'Stockage', 'Packs de ressources',
           'Activé', 'Supprimer (en quittant)', "Aucun pack. Importez-en un dans l'onglet Importer", 'Importer un pack',
           'Un fichier .mcres de cet appareil', 'Choisir un fichier .mcres...', 'Pack importé', "Échec de l'importation",
           'NOVA : le jeu a planté'],
 'it_IT': ['Mostra manichino', 'Schermata di debug (F3)', 'Dimensione manichino', 'Contatore FPS', 'Bordi dei chunk',
           'Scatto automatico (tasto avanti)', 'NOVA', 'Texture', 'Nuove texture (riavvio)', 'Archivio', 'Pacchetti risorse',
           'Attivo', 'Elimina (all\'uscita)', 'Nessun pacchetto. Importane uno nella scheda Importa', 'Importa pacchetto',
           'Un file .mcres da questo dispositivo', 'Scegli un file .mcres...', 'Pacchetto importato', 'Importazione fallita',
           'NOVA: il gioco si è chiuso'],
 'pt_BR': ['Mostrar boneco', 'Tela de depuração (F3)', 'Tamanho do boneco', 'Contador de FPS', 'Bordas de chunks',
           'Correr automático (botão para frente)', 'NOVA', 'Texturas', 'Texturas novas (reinicia)', 'Armazenamento',
           'Pacotes de recursos', 'Ativado', 'Excluir (ao sair)', 'Nenhum pacote. Importe um na aba Importar', 'Importar pacote',
           'Um arquivo .mcres deste aparelho', 'Escolher arquivo .mcres...', 'Pacote importado', 'Falha na importação',
           'NOVA: o jogo travou'],
 'ja_JP': ['プレイヤー人形を表示', 'デバッグ画面', '人形のサイズ', 'フレームレート表示', 'チャンクの境界', '自動ダッシュ（前進ボタン）',
           'NOVA', 'テクスチャ', '新しいテクスチャ（再起動）', 'ストレージ', 'リソースパック', '有効', '削除（終了時）',
           'パックがありません。インポートタブから追加してください', 'パックをインポート', 'この端末のパックファイル', 'ファイルを選択',
           'パックをインポートしました', 'インポートに失敗しました', 'ゲームがクラッシュしました'],
 'ko_KR': ['플레이어 인형 표시', '디버그 화면', '인형 크기', '프레임 표시', '청크 경계', '자동 달리기 (앞으로 버튼)',
           'NOVA', '텍스처', '새 텍스처 (재시작)', '저장소', '리소스 팩', '사용', '삭제 (나갈 때)',
           '팩이 없습니다. 가져오기 탭에서 추가하세요', '팩 가져오기', '이 기기의 팩 파일', '파일 선택',
           '팩을 가져왔습니다', '가져오기 실패', '게임이 충돌했습니다'],
 'zh_CN': ['显示人偶', '调试画面', '人偶大小', '帧率显示', '区块边界', '自动疾跑（前进键）',
           'NOVA', '纹理', '新纹理（重启）', '存储', '资源包', '已启用', '删除（退出时）',
           '还没有资源包。请在导入页导入', '导入资源包', '此设备上的资源包文件', '选择文件',
           '资源包已导入', '导入失败', '游戏崩溃了'],
 'zh_TW': ['顯示人偶', '除錯畫面', '人偶大小', '影格率顯示', '區塊邊界', '自動衝刺（前進鍵）',
           'NOVA', '材質', '新材質（重新啟動）', '儲存空間', '資源包', '已啟用', '刪除（離開時）',
           '還沒有資源包。請在匯入頁匯入', '匯入資源包', '此裝置上的資源包檔案', '選擇檔案',
           '資源包已匯入', '匯入失敗', '遊戲當機了'],
}
# mods (alpha5)
EXTRA = {
 'nova.mods.none': {'en_US': 'No mods yet. Import a .mcmod file on the Import tab', 'ru_RU': 'Модов нет. Импортируйте файл мода на вкладке «Импорт»',
                    'de_DE': 'Noch keine Mods. Importiere eine .mcmod-Datei im Tab Import', 'es_ES': 'No hay mods. Importa un archivo .mcmod en Importar',
                    'fr_FR': 'Aucun mod. Importez un fichier .mcmod dans Importer', 'it_IT': 'Nessuna mod. Importa un file .mcmod in Importa',
                    'pt_BR': 'Nenhum mod. Importe um arquivo .mcmod em Importar', 'ja_JP': 'MODがありません', 'ko_KR': '모드가 없습니다',
                    'zh_CN': '还没有模组', 'zh_TW': '還沒有模組'},
 'nova.toast.mods': {'en_US': 'Mods will start when you enter a world', 'ru_RU': 'Моды заработают при входе в мир',
                     'de_DE': 'Mods starten beim Betreten einer Welt', 'es_ES': 'Los mods se activan al entrar en un mundo',
                     'fr_FR': 'Les mods démarrent en entrant dans un monde', 'it_IT': 'Le mod partono entrando in un mondo',
                     'pt_BR': 'Os mods iniciam ao entrar em um mundo', 'ja_JP': 'ワールドに入るとMODが動きます', 'ko_KR': '월드에 들어가면 모드가 시작됩니다',
                     'zh_CN': '进入世界后模组生效', 'zh_TW': '進入世界後模組生效'},
}
for _k, _v in EXTRA.items():
    K.append(_k)
    for _l in _v:
        T[_l].append(_v[_l])

# accounts (alpha15): en for every language except ru
_ACC = [
 ('nova.acc.title', 'NOVA Account', 'Аккаунт NOVA'),
 ('nova.acc.login.group', 'Sign in', 'Вход'),
 ('nova.acc.reg.group', 'Registration', 'Регистрация'),
 ('nova.acc.tag', 'Player tag', 'Тег игрока'),
 ('nova.acc.pass', 'Password', 'Пароль'),
 ('nova.acc.pass2', 'Repeat password', 'Повтор пароля'),
 ('nova.acc.actions', 'Actions', 'Действия'),
 ('nova.acc.login', 'Sign in', 'Войти'),
 ('nova.acc.toreg', 'No account? Register', 'Нет аккаунта? Зарегистрироваться'),
 ('nova.acc.reg', 'Register', 'Зарегистрироваться'),
 ('nova.acc.tologin', 'Have an account? Sign in', 'Уже есть аккаунт? Войти'),
 ('nova.acc.hint', 'Tag: 3-16 letters, digits or _. It is not your nickname', 'Тег: от 3 до 16 английских букв, цифр или _. Это не ник'),
 ('nova.acc.profile', 'You are signed in', 'Вы вошли в аккаунт'),
 ('nova.acc.logout', 'Sign out', 'Выйти из аккаунта'),
 ('nova.acc.offline', 'No connection to the account server', 'Нет связи с сервером аккаунтов'),
 ('nova.acc.wait', 'Connecting...', 'Подключение'),
]
for _k, _en, _ru in _ACC:
    K.append(_k)
    for _l in T: T[_l].append(_ru if _l == 'ru_RU' else _en)
# alpha16: friends and playing together
_NET = [
 ('nova.start.signin', 'Sign in', 'Войти'),
 ('nova.start.friends', 'Friends', 'Друзья'),
 ('nova.pause.friends', 'Friends', 'Друзья'),
 ('nova.fr.title', 'Friends', 'Друзья'),
 ('nova.fr.add.group', 'Add a friend', 'Добавить друга'),
 ('nova.fr.send', 'Send a request', 'Отправить запрос'),
 ('nova.fr.add.hint', 'Ask your friend for the tag of the NOVA account (it is not the nickname)', 'Спросите у друга тег его аккаунта (это не ник)'),
 ('nova.fr.wants', 'Wants to be your friend', 'Хочет добавить вас в друзья'),
 ('nova.fr.accept', 'Accept', 'Принять'),
 ('nova.fr.decline', 'Decline', 'Отклонить'),
 ('nova.fr.join', 'Join', 'Подключиться'),
 ('nova.fr.remove', 'Remove', 'Удалить'),
 ('nova.fr.sent', 'Request sent', 'Запрос отправлен'),
 ('nova.fr.cancel', 'Cancel the request', 'Отменить запрос'),
 ('nova.fr.none', 'No friends yet. Add them on the + tab', 'Друзей пока нет. Добавьте их на вкладке +'),
 ('nova.fr.signin', 'Sign in to your NOVA account first', 'Сначала войдите в аккаунт'),
 ('nova.net.button', 'Network', 'Сеть'),
 ('nova.net.title', 'Network game', 'Сетевая игра'),
 ('nova.net.on', 'Play with friends', 'Сетевая игра'),
 ('nova.net.who', 'Who can join', 'Кто может заходить'),
 ('nova.net.friends', 'Friends can join', 'Друзья могут заходить'),
 ('nova.net.invite', 'Invited only', 'Только по приглашению'),
 ('nova.net.players', 'Players', 'Игроки'),
 ('nova.net.max', 'Max players', 'Максимум игроков'),
 ('nova.net.hint', 'Friends see the world while you play in it', 'Друзья видят мир, пока вы в нём играете'),
 ('nova.inv.title', 'Invite friends', 'Пригласить друзей'),
 ('nova.inv.send', 'Invite', 'Пригласить'),
 ('nova.inv.sent', 'Invited', 'Приглашение отправлено'),
 ('nova.inv.none', 'None of your friends is online', 'Никого из друзей нет в сети'),
 ('nova.ban.invited', 'You are invited', 'Вас пригласили'),
 ('nova.ban.join', 'Join', 'Войти'),
 ('nova.ban.no', 'No', 'Нет'),
 ('nova.fr.mods', 'Download the mods', 'Загрузить моды'),
 ('nova.net.modsgroup', 'Mods', 'Моды'),
 ('nova.net.mods', 'Friends get my mods', 'Передавать друзьям мои моды'),
]
for _k, _en, _ru in _NET:
    K.append(_k)
    for _l in T: T[_l].append(_ru if _l == 'ru_RU' else _en)
# import texts now cover mods too
_ii = K.index('nova.packs.import.sub')
T['ru_RU'][_ii] = 'Набор ресурсов или мод с этого устройства'
T['en_US'][_ii] = 'A resource pack or mod from this device'
_if = K.index('nova.packs.import.file')
T['ru_RU'][_if] = 'Выбрать файл'
T['en_US'][_if] = 'Choose a .mcres / .mcmod file...'
# start screen version label (ui_new/start_screen.json "version"): from the VERSION file
_vf = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'VERSION')
_ver = 'NOVA ' + (open(_vf).read().strip() if os.path.exists(_vf) else 'dev')
K.append('nova.version')
for _l in T: T[_l].append(_ver)
T['es_MX'] = T['es_ES']; T['fr_CA'] = T['fr_FR']; T['pt_PT'] = T['pt_BR']

def main(d):
    for f in sorted(os.listdir(d)):
        m = re.match(r'(.+)-pocket\.lang$', f)
        if not m: continue
        vals = dict(zip(K, T.get(m.group(1), T['en_US'])))
        p = os.path.join(d, f)
        lines = open(p, encoding='utf-8').read().split('\n')
        seen = set(); out = []
        for l in lines:
            k = l.split('=', 1)[0] if '=' in l else None
            if k in vals:
                out.append('%s=%s\t#' % (k, vals[k])); seen.add(k)
            else:
                out.append(l)
        while out and out[-1] == '': out.pop()
        for k in K:
            if k not in seen: out.append('%s=%s\t#' % (k, vals[k]))
        open(p, 'w', encoding='utf-8').write('\n'.join(out) + '\n')

if __name__ == '__main__':
    main(sys.argv[1])
