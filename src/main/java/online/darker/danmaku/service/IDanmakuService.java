package online.darker.danmaku.service;

import online.darker.danmaku.entity.DanmakuEntity;

import java.util.List;

public interface IDanmakuService {

    DanmakuEntity saveDanmaku(DanmakuEntity danmakuEntity) throws Exception;
    List<DanmakuEntity> listDanmakuById(String id, Integer max) throws Exception;

}
