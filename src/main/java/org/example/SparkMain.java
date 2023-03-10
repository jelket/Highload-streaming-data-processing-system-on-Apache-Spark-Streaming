package org.example;

import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.example.filters.FilterControl;
import org.example.filters.stringfilters.FilterShorterN;
import org.example.transformations.Transf;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.meta.derby.sys.Sys;
import org.jooq.sources.tables.Deduplication;
import org.jooq.sources.tables.Lego;

import javax.xml.crypto.Data;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SparkMain {
    private static Broadcast<List<Integer>> broadcastFil;
    private static Broadcast<List<String>> broadcastTra;
    private static Broadcast<String> broadcastDed;


    public static void main(String[] args) throws TimeoutException, StreamingQueryException, SQLException {

        // CTRL + ALT + L - форматирование кода
        // CTRL + ALT + O - избавление от ненужных импортов и объединение импортов

        //Обьявление всякого
        Integer[] fil = {2, 9};
        String[] tra = {"name" , "age"};
        String[] ded = {"age"};
        final String[] one = new String[1];
        final String[] two = new String[1];
        final String[] three = new String[1];
        final String[] DedupValue = new String[1];
        final Long[] DedupTime = new Long[1];


        //Переменные доступа к базе данных
        String userName = "postgres";
        String password = "postgres";
        String url = "jdbc:postgresql://localhost:5432/diploma";

        //Дефолтной запуск пайплайна спарка
        SparkSession spark = SparkSession
                .builder()
                .appName("JavaStructuredNetworkWordCount")
                .getOrCreate();

        JavaSparkContext sc = new JavaSparkContext(spark.sparkContext());

        spark.sparkContext().setLogLevel("ERROR");

        //Заполняем броадкасты рандомно чтоб пустыми не были
        broadcastFil = sc.broadcast(Arrays.asList(fil));
        broadcastTra = sc.broadcast(Arrays.asList(tra));
        broadcastDed = sc.broadcast(ded[0]);
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService serviceDB = Executors.newSingleThreadScheduledExecutor();


        FilterControl filterControl = new FilterControl(Arrays.asList(fil));
        Transf transf = new Transf();
        Dedup dedup = new Dedup(ded[0]);

        //Подтягиваем значения фильтра и т.п. раз в секунду
        service.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    Connection conn = DriverManager.getConnection(url, userName, password);
                    DSLContext create = DSL.using(conn, SQLDialect.POSTGRES);
                    Result<Record> result = create.select().from(Lego.LEGO).fetch();

                    for (Record r : result) {
                        one[0] = r.getValue(Lego.LEGO.FILTER);
                        two[0] = r.getValue(Lego.LEGO.TRANSFORM);
                        three[0] = r.getValue(Lego.LEGO.DEDUPLICATION);
                    }
                    Integer[] fil = Parser.myatoi(one[0]);
                    String[] tra = two[0].split(" ");
                    String[] ded = three[0].split(" ");
                    SparkMain.broadcastFil.destroy();
                    SparkMain.broadcastFil = sc.broadcast(Arrays.asList(fil));
                    filterControl.setFilters(broadcastFil.value());
                    SparkMain.broadcastTra.destroy();
                    SparkMain.broadcastTra = sc.broadcast(Arrays.asList(tra));
                    transf.setTransformValue(broadcastTra.value());
                    SparkMain.broadcastDed.destroy();
                    SparkMain.broadcastDed = sc.broadcast((ded[0]));
                    dedup.setDedupValue(broadcastDed.value());
                    System.out.println(System.currentTimeMillis());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        //Проверяем годность записей в таблице для дедупликации
        serviceDB.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    Connection connDB = DriverManager.getConnection(url, userName, password);
                    DSLContext createDB = DSL.using(connDB, SQLDialect.POSTGRES);
                    Result<Record> resultDB = createDB.select().from(Deduplication.DEDUPLICATION).fetch();

                    for (Record b : resultDB) {
                        DedupValue[0] = b.getValue(Deduplication.DEDUPLICATION.VALUE);
                        DedupTime[0] = b.getValue(Deduplication.DEDUPLICATION.TIMESTAMP);
                        if (DedupTime[0] <= System.currentTimeMillis() - 60000){
                            createDB.delete(Deduplication.DEDUPLICATION)
                                    .where(Deduplication.DEDUPLICATION.VALUE.eq(DedupValue[0]))
                                    .execute();
                        }
                        System.out.println("rabotaem");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, 100, TimeUnit.SECONDS);

        //Чтение из кафки
        Dataset<Row> df = spark
                .readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", "localhost:9092")
                .option("subscribe", "test")
                .load();

        df = df.selectExpr("CAST(value AS STRING)");

        //Отдаём строчку на фильт, парсинг внутри
        //Возвращается true | false. Строчка или проходит или нет
        Dataset<String> first = df
                .as(Encoders.STRING()) // десериализация в String
                .filter(filterControl);

        //Вызов трансформвции данный (обрезание полей) и запись в csv если указанно
        Dataset<String> second = first
                .map(transf, Encoders.STRING());

        Dataset<String> third = second
                .filter(dedup);
/*

        StreamingQuery query = df
                .writeStream()
                .outputMode("complete")
                .format("console")
                .start();
*/
        StreamingQuery query = third.writeStream().format("console").option("truncate", "False").start();
        query.awaitTermination();
    }
}
