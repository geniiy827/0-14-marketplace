<?php
/* Сборка StoryCraft.phar: php -d phar.readonly=0 tools/build_phar.php <out.phar> */
$src = realpath(__DIR__ . "/../StoryCraft");
$out = isset($argv[1]) ? $argv[1] : __DIR__ . "/../StoryCraft.phar";
@unlink($out);
$phar = new Phar($out);
$phar->setMetadata(["name" => "StoryCraft", "version" => "1.0.0", "main" => "rcode\\storycraft\\Main", "api" => ["2.0.0"], "creationDate" => time()]);
$phar->setStub('<?php echo "StoryCraft plugin for Genisys\n"; __HALT_COMPILER();');
$phar->startBuffering();
$it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($src, FilesystemIterator::SKIP_DOTS));
foreach ($it as $f) {
	$rel = str_replace("\\", "/", substr($f->getPathname(), strlen($src) + 1));
	$phar->addFile($f->getPathname(), $rel);
}
$phar->stopBuffering();
echo "OK: " . $out . "\n";
