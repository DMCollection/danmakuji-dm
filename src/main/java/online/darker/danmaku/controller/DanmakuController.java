package online.darker.danmaku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import online.darker.danmaku.constant.RedisKey;
import online.darker.danmaku.constant.ResponseType;
import online.darker.danmaku.constant.SecurityConstants;
import online.darker.danmaku.entity.DanmakuEntity;
import online.darker.danmaku.entity.ResponseEntity;
import online.darker.danmaku.service.IDanmakuService;
import online.darker.danmaku.utils.GeneralUtils;
import online.darker.danmaku.utils.JwtTokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/dplayer/v2")
public class DanmakuController {

    private static final Logger logger = LoggerFactory.getLogger(DanmakuController.class);

    private static final Long POST_FREQUENT_IP_TIME_OUT = 5L;
    private static final String BLACK_LIST_FILE_NAME = "blacklist";

    @Autowired
    private IDanmakuService danmakuService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @CrossOrigin
    @GetMapping
    public ResponseEntity getDanmakuList(@RequestParam("id") String id,
                                         @RequestParam(value = "max", required = false, defaultValue = "1000") Integer max) {
        ResponseEntity responseEntity = new ResponseEntity();
        try {
            List<DanmakuEntity> danmakuEntityList = danmakuService.listDanmakuById(id, max);
            if (danmakuEntityList != null && danmakuEntityList.size() != 0) {
                responseEntity.setDanmaku(parseDanmakuListToArray(danmakuEntityList));
            } else {
                responseEntity.setDanmaku(new ArrayList<>());
            }
            responseEntity.setCode(ResponseType.SUCCESS);
            responseEntity.setMsg("");
            return responseEntity;
        } catch (Exception e) {
            e.printStackTrace();
            logger.info("数据库出错");
            responseEntity.setCode(ResponseType.DATABASE_ERROR);
            responseEntity.setMsg("数据库出现了点偏差");
            return responseEntity;
        }
    }

    @CrossOrigin
    @PostMapping
    public ResponseEntity postDanmaku(HttpServletRequest request, HttpServletResponse response) throws IOException {

        DanmakuEntity de = new ObjectMapper().readValue(request.getInputStream(), DanmakuEntity.class);
        ResponseEntity responseEntity = new ResponseEntity();
        // 先验证token
        String header = request.getHeader(SecurityConstants.TOKEN_HEADER_AUTHORIZATION);
        if (header == null) {
            header = de.getToken();
        }
        if (header == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            responseEntity.setMsg("请先登录后再发弹幕");
            responseEntity.setCode(ResponseType.PERMISSION_DENY);
            return responseEntity;
        }

        String token = header.replace(SecurityConstants.TOKEN_PREFIX, "");
        if (!jwtTokenUtils.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            responseEntity.setMsg("无效的登录凭证或该凭证已过期，请重新登录");
            responseEntity.setCode(ResponseType.PERMISSION_DENY);
            return responseEntity;
        }

        String author = de.getAuthor();
        String color = de.getColor();
        double time = de.getTime();
        String player = de.getPlayer();
        String text = de.getText();
        String type = de.getType();
        String ip = GeneralUtils.getIpAddress(request);

        logger.info("请求参数 :{} ip :{}", de, ip);


        // 去除黑名单


        String fequentIpKey = RedisKey.POST_FREQUENT_IP_KEY + ip;
        if (stringRedisTemplate.hasKey(fequentIpKey)) {
            logger.info("ip为 [{}] 访问频繁");
            responseEntity.setCode(ResponseType.FREQUENT_OPERATION);
            responseEntity.setMsg("你发弹幕太快啦!稍后再试试吧!");
            return responseEntity;
        } else {
            stringRedisTemplate.opsForValue().set(fequentIpKey, ip, POST_FREQUENT_IP_TIME_OUT, TimeUnit.SECONDS);
        }

        if (isEmpty(author) || isEmpty(color) || isEmpty(player)
                || isEmpty(text) || isEmpty(type)) {
            responseEntity.setCode(ResponseType.ILLEGAL_DATA);
            responseEntity.setMsg("数据异常");
            return responseEntity;
        }


        DanmakuEntity danmakuEntity = new DanmakuEntity();
        danmakuEntity.setAuthor(GeneralUtils.htmlEncode(author));
        danmakuEntity.setColor(GeneralUtils.htmlEncode(color));
        danmakuEntity.setPlayer(GeneralUtils.htmlEncode(player));
        danmakuEntity.setText(GeneralUtils.htmlEncode(text));
        danmakuEntity.setTime(time);
        danmakuEntity.setType(GeneralUtils.htmlEncode(type));
        danmakuEntity.setIpAddress(ip);

        try {
            DanmakuEntity danmaku = danmakuService.saveDanmaku(danmakuEntity);
            responseEntity.setCode(ResponseType.SUCCESS);
            responseEntity.setMsg("ok");
            responseEntity.setDanmaku(parseDanmakuListToArray(Collections.singletonList(danmaku)));
            return responseEntity;
        } catch (Exception e) {
            e.printStackTrace();
            logger.info("数据库出现了点偏差");
            responseEntity.setCode(ResponseType.DATABASE_ERROR);
            responseEntity.setMsg("数据库出现了点偏差");
            return responseEntity;
        }
    }

    private boolean isEmpty(String string) {
        return StringUtils.isEmpty(string);
    }

    /**
     * 将弹幕type转成int
     *
     * @param type 弹幕type
     * @return 弹幕代号
     */
    private int parseTypeToInt(String type) {
        if (type.equals("right")) {
            return 0;
        }
        if (type.equals("top")) {
            return 1;
        }
        if (type.equals("bottom")) {
            return 2;
        }
        return 0;
    }

    /**
     * 将弹幕数据包装成dplayer能识别的格式
     *
     * @param danmakuEntities 弹幕列表
     * @return 弹幕列表
     */
    private List<Object[]> parseDanmakuListToArray(List<DanmakuEntity> danmakuEntities) {
        List<Object[]> data = new ArrayList<>();
        if (danmakuEntities != null && danmakuEntities.size() != 0) {
            for (DanmakuEntity de : danmakuEntities) {
                Object[] danmaku = new Object[]{de.getTime(), parseTypeToInt(de.getType()), de.getColor(), de.getAuthor(), de.getText()};
                data.add(danmaku);
            }
            return data;
        }
        return data;
    }
}