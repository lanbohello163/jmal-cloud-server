package com.jmal.clouddisk.service.impl;

import cn.hutool.core.io.CharsetDetector;
import cn.hutool.core.io.FileTypeUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import com.jmal.clouddisk.config.FileProperties;
import com.jmal.clouddisk.model.FileIndex;
import com.jmal.clouddisk.model.FileIntroVO;
import com.jmal.clouddisk.model.query.SearchDTO;
import com.jmal.clouddisk.service.Constants;
import com.jmal.clouddisk.service.IUserService;
import com.jmal.clouddisk.util.*;
import com.mongodb.client.AggregateIterable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author jmal
 *
 * 优化索引
 * indexWriter.forceMerge(1);
 * indexWriter.commit();
 *
 * @Description LuceneService
 * @Date 2021/4/27 4:44 下午
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LuceneService {

    private final FileProperties fileProperties;
    private final Analyzer analyzer;
    private final MongoTemplate mongoTemplate;
    private final IndexWriter indexWriter;
    private final SearcherManager searcherManager;

    /**
     * 新建索引文件缓冲队列大小
     */
    private final static int CREATE_INDEX_QUEUE_SIZE = 256;

    private ArrayBlockingQueue<FileIndex> indexFileQueue;

    /**
     * 推送至新建索引文件缓存队列
     *
     * @param fileIndex     fileIndex
     */
    public void pushCreateIndexQueue(FileIndex fileIndex) {
        if (fileIndex == null) {
            return;
        }
        setFileIndex(fileIndex);
        try {
            ArrayBlockingQueue<FileIndex> indexFileQueue = getIndexFileQueue();
            indexFileQueue.put(fileIndex);
        } catch (InterruptedException e) {
            log.error("推送新建索引队列失败, fileId: {}, {}", fileIndex.getFileId(), e.getMessage(), e);
        }
    }

    private ArrayBlockingQueue<FileIndex> getIndexFileQueue() {
        if (indexFileQueue == null) {
            indexFileQueue = new ArrayBlockingQueue<>(CREATE_INDEX_QUEUE_SIZE);
            createIndexFileTask();
        }
        return indexFileQueue;
    }

    /**
     * 新建索引文件任务
     *
     */
    private void createIndexFileTask() {
        ArrayBlockingQueue<FileIndex> indexFileQueue = getIndexFileQueue();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<FileIndex> fileIndexList = new ArrayList<>(indexFileQueue.size());
                indexFileQueue.drainTo(fileIndexList);
                if (!fileIndexList.isEmpty()) {
                    createIndexFiles(fileIndexList);
                }
            } catch (Exception e) {
                log.error("创建索引失败", e);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * 创建索引
     * @param fileIndexList List<FileIndex>
     * @throws IOException IOException
     */
    private void createIndexFiles(List<FileIndex> fileIndexList) throws IOException {
        for (FileIndex fileIndex : fileIndexList) {
            File file = fileIndex.getFile();
            String content = readFileContent(file);
            updateIndexDocument(indexWriter, fileIndex, content);
            if (StrUtil.isNotBlank(content)) {
                log.info("添加索引, filepath: {}", file.getAbsoluteFile());
            }
        }
        indexWriter.commit();
        removeDeletedFlag(fileIndexList);
    }

    private void removeDeletedFlag(List<FileIndex> fileIndexList) {
        // 提取出fileIdList
        List<String> fileIdList = fileIndexList.stream().map(FileIndex::getFileId).toList();
        // 移除删除标记
        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
        query.addCriteria(Criteria.where("_id").in(fileIdList).and("delete").is(1));
        Update update = new Update();
        update.unset("delete");
        mongoTemplate.updateMulti(query, update, CommonFileService.COLLECTION_NAME);
    }

    /**
     * 构建FileIndex
     *
     * @param fileIndex FileIndex
     */
    public void setFileIndex(FileIndex fileIndex) {
        File file = fileIndex.getFile();
        if (!FileUtil.exist(fileIndex.getFile())) {
            return;
        }
        fileIndex.setIsFolder(file.isDirectory());
        fileIndex.setName(file.getName());
        try {
            fileIndex.setModified(Files.getLastModifiedTime(file.toPath()).toMillis());
        } catch (IOException e) {
            log.error("获取文件修改时间失败, file: {}, {}", file.getAbsolutePath(), e.getMessage(), e);
        }
        fileIndex.setSize(file.length());
        setType(file, fileIndex);
    }

    private void setType(File file, FileIndex fileIndex) {
        String fileName = file.getName();
        String suffix = FileUtil.extName(fileName);
        if (StrUtil.isBlank(suffix)) {
            fileIndex.setType(Constants.OTHER);
            return;
        }
        String contentType = FileContentTypeUtils.getContentType(suffix);
        if (StrUtil.isBlank(suffix)) {
            fileIndex.setType(Constants.OTHER);
            return;
        }
        if (contentType.startsWith(Constants.CONTENT_TYPE_IMAGE)) {
            fileIndex.setType(Constants.CONTENT_TYPE_IMAGE);
        }
        if (contentType.startsWith(Constants.VIDEO)) {
            fileIndex.setType(Constants.VIDEO);
        }
        if (contentType.startsWith(Constants.AUDIO)) {
            fileIndex.setType(Constants.AUDIO);
        }
        if (fileProperties.getDocument().contains(suffix)) {
            fileIndex.setType(Constants.DOCUMENT);
        }
    }

    private String readFileContent(File file) {
        try {
            if (file == null) {
                return null;
            }
            if (!file.isFile() || file.length() < 1) {
                return null;
            }
            String type = FileTypeUtil.getType(file);
            if ("pdf".equals(type)) {
                return FileContentUtil.readPdfContent(file);
            }
            if ("ppt".equals(type) || "pptx".equals(type)) {
                return FileContentUtil.readPPTContent(file);
            }
            if ("doc".equals(type) || "docx".equals(type)) {
                return FileContentUtil.readWordContent(file);
            }
            Charset charset = CharsetDetector.detect(file);
            if (charset == null) {
                return null;
            }
            if ("UTF-8".equals(charset.toString())) {
                if (fileProperties.getSimText().contains(type)) {
                    return FileUtil.readUtf8String(file);
                }
            }
        } catch (Exception e) {
            log.error("读取文件内容失败, file: {}, {}", file.getAbsolutePath(), e.getMessage(), e);
        }
        return null;
    }


    public void deleteIndexDocuments(List<String> fileIds) {
        try {
            for (String fileId : fileIds) {
                Term term = new Term("id", fileId);
                indexWriter.deleteDocuments(term);
            }
            indexWriter.commit();
        } catch (IOException e) {
            log.error("删除索引失败, fileIds: {}, {}", fileIds, e.getMessage(), e);
        }
    }

    /**
     * 添加/更新索引
     * @param indexWriter indexWriter
     * @param fileIndex FileIndex
     * @param content content
     */
    public void updateIndexDocument(IndexWriter indexWriter, FileIndex fileIndex, String content) {
        String fileId = fileIndex.getFileId();
        try {
            String fileName = fileIndex.getName();
            String tagName = fileIndex.getTagName();
            Boolean isFolder = fileIndex.getIsFolder();
            Boolean isFavorite = fileIndex.getIsFavorite();
            String path = fileIndex.getPath();
            org.apache.lucene.document.Document newDocument = new org.apache.lucene.document.Document();
            newDocument.add(new StringField("id", fileId, Field.Store.YES));
            newDocument.add(new StringField("userId", fileIndex.getUserId(), Field.Store.NO));
            if (fileIndex.getType() != null) {
                newDocument.add(new StringField("type", fileIndex.getType(), Field.Store.NO));
            }
            if (StrUtil.isNotBlank(fileName)) {
                newDocument.add(new StringField("name", fileName.toLowerCase(), Field.Store.NO));
            }
            if (isFolder != null) {
                newDocument.add(new IntPoint("isFolder", isFolder ? 1 : 0));
            }
            if (isFavorite != null) {
                newDocument.add(new IntPoint("isFavorite", isFavorite ? 1 : 0));
            }
            if (path != null) {
                newDocument.add(new StringField("path", path, Field.Store.NO));
            }
            if (StrUtil.isNotBlank(tagName)) {
                newDocument.add(new StringField("tag", tagName.toLowerCase(), Field.Store.NO));
            }
            if (StrUtil.isNotBlank(content)) {
                newDocument.add(new TextField("content", content, Field.Store.NO));
            }
            if (fileIndex.getModified() != null) {
                newDocument.add(new NumericDocValuesField("modified", fileIndex.getModified()));
            }
            if (fileIndex.getSize() != null) {
                newDocument.add(new NumericDocValuesField("size", fileIndex.getSize()));
            }
            indexWriter.updateDocument(new Term("id", fileId), newDocument);
        } catch (IOException e) {
            log.error("更新索引失败, fileId: {}, {}", fileId, e.getMessage(), e);
        }
    }

    public ResponseResult<List<FileIntroVO>> searchFile(SearchDTO searchDTO) {
        String keyword = searchDTO.getKeyword();
        if (keyword == null || keyword.trim().isEmpty() || searchDTO.getUserId() == null) {
            return ResultUtil.success(Collections.emptyList());
        }
        try {
            int pageNum = searchDTO.getPage();
            int pageSize = searchDTO.getPageSize();
            searcherManager.maybeRefresh();
            List<String> seenIds = new ArrayList<>();
            IndexSearcher indexSearcher = searcherManager.acquire();
            Query query = getQuery(searchDTO);
            Sort sort = getSort(searchDTO);
            log.info("搜索关键字: {}", query.toString());
            log.info("排序规则: {}", sort);
            ScoreDoc lastScoreDoc = null;
            if (pageNum > 1) {
                int totalHitsToSkip = (pageNum - 1) * pageSize;
                TopDocs topDocs = indexSearcher.search(query, totalHitsToSkip, sort);
                if (topDocs.scoreDocs.length == totalHitsToSkip) {
                    lastScoreDoc = topDocs.scoreDocs[totalHitsToSkip - 1];
                }
            }
            int count = indexSearcher.count(query);
            TopDocs topDocs = indexSearcher.searchAfter(lastScoreDoc, query, pageSize, sort);
            for (ScoreDoc hit : topDocs.scoreDocs) {
                indexSearcher.storedFields().document(hit.doc);
                Document doc = indexSearcher.storedFields().document(hit.doc);
                String id = doc.get("id");
                seenIds.add(id);
            }
            List<FileIntroVO> fileIntroVOList = getFileDocuments(seenIds);
            return ResultUtil.success(fileIntroVOList).setCount(count);
        } catch (IOException | ParseException e) {
            log.error("搜索失败", e);
        }

        return ResultUtil.success(new ArrayList<>());
    }

    /**
     * 获取排序规则
     * @param searchDTO searchDTO
     * @return Sort
     */
    private static Sort getSort(SearchDTO searchDTO) {
        String sortProp = searchDTO.getSortProp();
        String sortOrder = searchDTO.getSortOrder();
        if (StrUtil.isBlank(sortProp) || StrUtil.isBlank(sortOrder)) {
            return new Sort(SortField.FIELD_SCORE);
        }
        // 创建排序规则
        SortField sortField;
        if ("updateDate".equals(searchDTO.getSortProp())) {
            sortField = new SortField("modified", SortField.Type.LONG, "descending".equalsIgnoreCase(searchDTO.getSortOrder()));
        } else if ("size".equals(searchDTO.getSortProp())) {
            sortField = new SortField("size", SortField.Type.LONG, "descending".equalsIgnoreCase(searchDTO.getSortOrder()));
        } else {
            // 默认按相关性得分排序
            sortField = SortField.FIELD_SCORE;
        }
        return new Sort(sortField);
    }

    /**
     * 构建查询器
     *
     * @param searchDTO searchDTO
     * @return Query
     * @throws ParseException ParseException
     */
    private Query getQuery(SearchDTO searchDTO) throws ParseException {
        String[] fields = {"name", "tag", "content"};
        Map<String, Float> boosts = new HashMap<>();
        boosts.put("name", 3.0f);
        boosts.put("tag", 2.0f);
        boosts.put("content", 1.0f);

        // 将关键字转为小写
        String keyword = searchDTO.getKeyword().toLowerCase();

        BooleanQuery.Builder regexpQueryBuilder = new BooleanQuery.Builder();
        for (String field : fields) {
            if ("content".equals(field)) {
                continue;
            }
            regexpQueryBuilder.add(new BoostQuery(new RegexpQuery(new Term(field, ".*" + keyword + ".*")), boosts.get(field)), BooleanClause.Occur.SHOULD);
        }
        Query regExpQuery = regexpQueryBuilder.build();
        BoostQuery boostedRegExpQuery = new BoostQuery(regExpQuery, 10.0f);

        BooleanQuery.Builder phraseQueryBuilder = new BooleanQuery.Builder();
        for (String field : fields) {
            PhraseQuery.Builder builder = new PhraseQuery.Builder();
            builder.add(new Term(field, keyword.trim()));
            Query phraseQuery = builder.build();
            phraseQueryBuilder.add(new BoostQuery(phraseQuery, boosts.get(field)), BooleanClause.Occur.SHOULD);
        }
        Query phraseQuery = phraseQueryBuilder.build();

        // 创建分词匹配查询
        BooleanQuery.Builder tokensQueryBuilder = new BooleanQuery.Builder();
        for (String field : fields) {
            QueryParser parser = new QueryParser(field, analyzer);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            Query query = parser.parse(keyword.trim());
            tokensQueryBuilder.add(new BoostQuery(query, boosts.get(field)), BooleanClause.Occur.SHOULD);
        }
        Query tokensQuery = tokensQueryBuilder.build();


        // 将正则表达式查询、短语查询和分词匹配查询组合成一个查询（OR关系）
        BooleanQuery.Builder combinedQueryBuilder = new BooleanQuery.Builder();
        combinedQueryBuilder.add(boostedRegExpQuery, BooleanClause.Occur.SHOULD);
        combinedQueryBuilder.add(phraseQuery, BooleanClause.Occur.SHOULD);
        combinedQueryBuilder.add(tokensQuery, BooleanClause.Occur.SHOULD);
        Query combinedQuery = combinedQueryBuilder.build();

        // 创建最终查询（AND关系）
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new TermQuery(new Term(IUserService.USER_ID, searchDTO.getUserId())), BooleanClause.Occur.MUST);
        if (StrUtil.isNotBlank(searchDTO.getType())) {
            builder.add(new TermQuery(new Term("type", searchDTO.getType())), BooleanClause.Occur.MUST);
        }
        if (StrUtil.isNotBlank(searchDTO.getCurrentDirectory())) {
            Term prefixTerm = new Term("path", searchDTO.getCurrentDirectory());
            PrefixQuery prefixQuery = new PrefixQuery(prefixTerm);
            builder.add(prefixQuery, BooleanClause.Occur.MUST);
        }
        if (searchDTO.getIsFolder() != null) {
            builder.add(IntPoint.newExactQuery("isFolder", searchDTO.getIsFolder() ? 1 : 0), BooleanClause.Occur.MUST);
        }
        if (searchDTO.getIsFavorite() != null) {
            builder.add(IntPoint.newExactQuery("isFavorite", searchDTO.getIsFavorite() ? 1 : 0), BooleanClause.Occur.MUST);
        }
        builder.add(combinedQuery, BooleanClause.Occur.MUST);

        return builder.build();

    }

    public List<FileIntroVO> getFileDocuments(List<String> files) {
        List<ObjectId> objectIds = files.stream()
                .filter(ObjectId::isValid)
                .map(ObjectId::new)
                .toList();
        List<org.bson.Document> pipeline = Arrays.asList(new org.bson.Document("$match",
                        new org.bson.Document("_id",
                                new org.bson.Document("$in", objectIds))),
                new org.bson.Document("$addFields",
                        new org.bson.Document("order",
                                new org.bson.Document("$indexOfArray", Arrays.asList(objectIds, "$_id")))),
                new org.bson.Document("$sort",
                        new org.bson.Document("order", 1L)),
                new org.bson.Document("$project",
                        new org.bson.Document("order", 0L)
                                .append("contentText", 0L)));

        AggregateIterable<org.bson.Document> aggregateIterable = mongoTemplate.getCollection(CommonFileService.COLLECTION_NAME)
                .aggregate(pipeline)
                .allowDiskUse(true);

        List<FileIntroVO> results = new ArrayList<>();
        for (org.bson.Document document : aggregateIterable) {
            FileIntroVO fileIntroVO = mongoTemplate.getConverter().read(FileIntroVO.class, document);
            results.add(fileIntroVO);
        }

        return results;
    }

    public boolean checkIndexExists() {
        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
        query.addCriteria(Criteria.where("ossFolder").exists(true));
        long count = mongoTemplate.count(query, CommonFileService.COLLECTION_NAME);
        long indexCount = indexWriter.getDocStats().numDocs;
        return indexCount > count;
    }

    public void deleteAllIndex(String userId) {
        try {
            // 创建一个Term来指定userId字段和要删除的具体userId
            Term term = new Term(IUserService.USER_ID, userId);
            // 删除所有匹配此Term的文档
            indexWriter.deleteDocuments(term);
            // 提交更改
            indexWriter.commit();
            addDeleteFlagOfDoc(userId);
            log.info("所有userId为 {} 的索引已被删除", userId);
        } catch (IOException e) {
            log.error("删除索引失败, userId: {}, {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 添加删除标记
     * @param userId userId
     */
    private void addDeleteFlagOfDoc(String userId) {
        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
        query.addCriteria(Criteria.where(IUserService.USER_ID).is(userId));
        Update update = new Update();
        update.set("delete", 1);
        mongoTemplate.updateMulti(query, update, CommonFileService.COLLECTION_NAME);
    }

    public static void main(String[] args) throws IOException {
        File file = new File("/Users/jmal/temp/filetest/rootpath/jmal/未命名文未命名文件未命名文件未命名文件件.drawio");
        // String content = FileUtil.readUtf8String(file);
        String content = "BetterDisplay-v2.2.6.dmg";
        Console.log(StringUtil.isContainChinese(content));
        //1.创建一个Analyzer对象
        Analyzer analyzer = new SmartChineseAnalyzer();
        //2.调用Analyzer对象的tokenStream方法获取TokenStream对象，此对象包含了所有的分词结果
        TokenStream tokenStream = analyzer.tokenStream("", content);
        //3.给tokenStream对象设置一个指针，指针在哪当前就在哪一个分词上
        CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);
        //4.调用tokenStream对象的reset方法，重置指针，不调用会报错
        tokenStream.reset();
        //5.利用while循环，拿到分词列表的结果  incrementToken方法返回值如果为false代表读取完毕  true代表没有读取完毕
        while (tokenStream.incrementToken()) {
            String word = charTermAttribute.toString();
            System.out.println(word);
        }
        //6.关闭
        tokenStream.close();
        analyzer.close();
    }

}
