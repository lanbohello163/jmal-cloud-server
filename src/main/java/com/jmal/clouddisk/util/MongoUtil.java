package com.jmal.clouddisk.util;

import cn.hutool.core.bean.BeanUtil;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @author jmal
 * @Description mongodb工具类
 * @Date 2020/10/28 10:55 上午
 */
public class MongoUtil {

    /***
     * 需要排除的字段
     */
    private static final List<String> DEFAULT_EXCLUDES  = Arrays.asList("id", "_id");

    /**
     * 获取mongodb更新对象
     * @param source
     * @return
     */
    public static Update getUpdate(Object source) {
        return getUpdate(source, DEFAULT_EXCLUDES);
    }

    /**
     * 获取mongodb更新对象
     * @param source 源对象
     * @param excludeList 要排除的字段列表
     * @return
     */
    public static Update getUpdate(Object source, List<String> excludeList) {
        Update update = new Update();
        Map<String, Object> categoryDTOMap = BeanUtil.beanToMap(source);
        for (Map.Entry<String, Object> objectEntry : categoryDTOMap.entrySet()) {
            if(excludeList.contains(objectEntry.getKey())){
                continue;
            }
            if(objectEntry.getValue() != null){
                update.set(objectEntry.getKey(), objectEntry.getValue());
            }
        }
        return update;
    }

}
