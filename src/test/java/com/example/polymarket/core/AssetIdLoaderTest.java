package com.example.polymarket.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class AssetIdLoaderTest {
    @TempDir Path dir;
    private AssetIdLoader loader(){return new AssetIdLoader(new DefaultResourceLoader(),new ObjectMapper());}
    @Test void loadsLinesCommaAndJson() throws Exception {Path lines=dir.resolve("lines.txt"),comma=dir.resolve("comma.txt"),json=dir.resolve("json.txt");Files.writeString(lines,"# c\na\nb\n");Files.writeString(comma,"a,b,c");Files.writeString(json,"[\"a\",\"b\"]");assertThat(loader().load(lines.toUri().toString())).containsExactly("a","b");assertThat(loader().load(comma.toUri().toString())).containsExactly("a","b","c");assertThat(loader().load(json.toUri().toString())).containsExactly("a","b");}
    @Test void rejectsMissingAndEmpty() throws Exception {Path empty=dir.resolve("empty.txt");Files.writeString(empty,"# only comment\n");assertThatThrownBy(()->loader().load(dir.resolve("missing.txt").toUri().toString())).hasMessageContaining("不存在");assertThatThrownBy(()->loader().load(empty.toUri().toString())).hasMessageContaining("为空");}
}
