package com.jmal.clouddisk.controller.rest;

import cn.hutool.extra.cglib.CglibUtil;
import com.jmal.clouddisk.model.Category;
import com.jmal.clouddisk.model.CategoryDTO;
import com.jmal.clouddisk.model.Tag;
import com.jmal.clouddisk.model.TagDTO;
import com.jmal.clouddisk.service.impl.CategoryService;
import com.jmal.clouddisk.service.impl.TagService;
import com.jmal.clouddisk.util.ResponseResult;
import com.jmal.clouddisk.util.ResultUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * @author jmal
 * @Description 标签管理
 * @Date 2020/12/02 6:47 下午
 */
@RestController
@Api(tags = "标签管理")
public class TagController {

    @Autowired
    private TagService tagService;

    @ApiOperation("标签列表")
    @GetMapping("/tag/list")
    public ResponseResult<List<TagDTO>> list(String userId) {
        return ResultUtil.success(tagService.list(userId));
    }

    @ApiOperation("标签信息")
    @GetMapping("/tag/info")
    @ResponseBody
    public ResponseResult<TagDTO> tagInfo(@RequestParam String tagId) {
        TagDTO tagDTO = new TagDTO();
        Tag tag = tagService.getTagInfo(tagId);
        if(tag != null){
            CglibUtil.copy(tag, tagDTO);
        }
        return ResultUtil.success(tagDTO);
    }

    @ApiOperation("添加标签")
    @PostMapping("/tag/add")
    public ResponseResult<Object> add(@ModelAttribute @Validated TagDTO tagDTO) {
        return tagService.add(tagDTO);
    }

    @ApiOperation("更新标签")
    @PutMapping("/tag/update")
    @ResponseBody
    public ResponseResult<Object> update(@ModelAttribute @Validated TagDTO tagDTO) {
        return tagService.update(tagDTO);
    }

    @ApiOperation("删除标签")
    @DeleteMapping("/tag/delete")
    public ResponseResult<Object> delete(@RequestParam String[] tagIds) {
        List<String> tagIdList = Arrays.asList(tagIds);
        tagService.delete(tagIdList);
        return ResultUtil.success();
    }
}