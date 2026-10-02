package dev.voxydistant.client;

import com.mojang.logging.LogUtils;
import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.data.LodDatabase;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One startup pass over existing caches; it does not create a Voxy world or load terrain. */
public final class CacheIndexStartup {
    private static final org.slf4j.Logger LOG=LogUtils.getLogger();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"Voxy Distant startup index");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final ExecutorService readers=Executors.newFixedThreadPool(6,r->{var t=new Thread(r,"Voxy Distant index reader");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final ArrayDeque<Path> paths=new ArrayDeque<>();
    private final CompletableFuture<Void> completion=new CompletableFuture<>();
    private Path current;
    private CompletableFuture<LodDatabase> database;
    private long started,reported;
    private boolean announced;
    public static CompletableFuture<Void> start(Path gameDirectory){
        var task=new CacheIndexStartup();task.worker.execute(()->{
            try{
                var found=new LinkedHashSet<Path>();
                for(Path root:List.of(gameDirectory.resolve(".voxy/saves"),gameDirectory.resolve("saves"))){
                    if(!Files.isDirectory(root))continue;
                    try(var entries=Files.list(root)){
                        for(Path world:entries.toList()){
                            Path directory=root.equals(gameDirectory.resolve("saves"))?world.resolve("voxy/distant-coverage"):world.resolve("distant-coverage");
                            if(!Files.isDirectory(directory))continue;
                            try(var files=Files.list(directory)){
                                for(Path file:files.toList()){String name=file.getFileName().toString();if(name.endsWith(".bin.rocksdb")&&Files.isDirectory(file))found.add(file);else if(name.endsWith(".bin")&&Files.isRegularFile(file))found.add(file.resolveSibling(name+".rocksdb"));}
                            }
                        }
                    }
                }
                task.paths.addAll(found);LOG.info("Voxy Distant 客户端启动索引检查：发现 {} 个世界缓存，无需进入世界",found.size());task.step();
            }catch(IOException|RuntimeException e){task.fail(e);}
        });
        return task.completion;
    }
    private void step(){
        try{
            if(current==null){
                if(paths.isEmpty()){LOG.info("Voxy Distant 客户端启动索引检查完成");readers.shutdown();worker.shutdown();completion.complete(null);return;}
                current=paths.removeFirst();database=CoverageStore.openDatabase(current);started=reported=System.nanoTime();announced=false;
            }
            if(!database.isDone()){worker.schedule(this::step,10,TimeUnit.MILLISECONDS);return;}
            var db=database.join();
            if(!announced){announced=true;LOG.info(CoverageStore.directoriesComplete(db)?"Voxy Distant 客户端索引已就绪，跳过重建：{}":"Voxy Distant 客户端开始补建缓存索引：{}（6 线程，支持断点续建）",current);}
            var progress=CoverageStore.rebuildDirectoriesStep(db,readers);long now=System.nanoTime();
            if(progress.complete()){
                LOG.info("Voxy Distant 客户端缓存索引就绪：{}，{} 页，耗时 {} ms",current,progress.pages(),(now-started)/1_000_000);
                CoverageStore.closeDatabase(current);current=null;database=null;
            }else if(now-reported>=TimeUnit.SECONDS.toNanos(5)){reported=now;db.sync();LOG.info("Voxy Distant 客户端缓存索引进度：{}，已检查 {} 页，耗时 {} s",current,progress.pages(),(now-started)/1_000_000_000);}
            worker.execute(this::step);
        }catch(RuntimeException e){fail(e);}
    }
    private void fail(Exception failure){
        LOG.error("Voxy Distant 客户端启动索引失败：{}；已提交进度保留，下次启动继续",current,failure);
        try{if(current!=null)CoverageStore.closeDatabase(current);}finally{readers.shutdown();worker.shutdown();completion.completeExceptionally(failure);}
    }
    private CacheIndexStartup(){}
}
