/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.bind.DatatypeConverter;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import io.debezium.connector.SnapshotRecord;
import io.debezium.data.Bits;
import io.debezium.data.Json;
import io.debezium.data.SchemaUtil;
import io.debezium.data.Uuid;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.data.VerifyRecord;
import io.debezium.data.Xml;
import io.debezium.data.geometry.Geography;
import io.debezium.data.geometry.Geometry;
import io.debezium.data.geometry.Point;
import io.debezium.function.BlockingConsumer;
import io.debezium.relational.TableId;
import io.debezium.time.Date;
import io.debezium.time.MicroDuration;
import io.debezium.time.MicroTime;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Time;
import io.debezium.time.Timestamp;
import io.debezium.time.ZonedTime;
import io.debezium.time.ZonedTimestamp;
import io.debezium.util.VariableLatch;

/**
 * Base class for the integration tests for the different {@link RecordsProducer} instances
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
public abstract class AbstractRecordsProducerTest {

    protected static final Pattern INSERT_TABLE_MATCHING_PATTERN = Pattern.compile("insert into (.*)\\(.*\\) VALUES .*", Pattern.CASE_INSENSITIVE);

    protected static final String INSERT_CASH_TYPES_STMT = "INSERT INTO cash_table (csh) VALUES ('$1234.11')";
    protected static final String INSERT_DATE_TIME_TYPES_STMT = "INSERT INTO time_table(ts, tsneg, ts_ms, ts_us, tz, date, ti, tip, ttf, ttz, tptz, it) " +
                                                                "VALUES ('2016-11-04T13:51:30.123456'::TIMESTAMP, '1936-10-25T22:10:12.608'::TIMESTAMP, '2016-11-04T13:51:30.123456'::TIMESTAMP, '2016-11-04T13:51:30.123456'::TIMESTAMP, '2016-11-04T13:51:30.123456+02:00'::TIMESTAMPTZ, " +
                                                                "'2016-11-04'::DATE, '13:51:30'::TIME, '13:51:30.123'::TIME, '24:00:00'::TIME, '13:51:30.123789+02:00'::TIMETZ, '13:51:30.123+02:00'::TIMETZ, " +
                                                                "'P1Y2M3DT4H5M0S'::INTERVAL)";
    protected static final String INSERT_BIN_TYPES_STMT = "INSERT INTO bitbin_table (ba, bol, bs, bv) " +
                                                          "VALUES (E'\\\\001\\\\002\\\\003'::bytea, '0'::bit(1), '11'::bit(2), '00'::bit(2))";
    protected static final String INSERT_GEOM_TYPES_STMT = "INSERT INTO geom_table(p) VALUES ('(1,1)'::point)";
    protected static final String INSERT_TEXT_TYPES_STMT = "INSERT INTO text_table(j, jb, x, u) " +
                                                           "VALUES ('{\"bar\": \"baz\"}'::json, '{\"bar\": \"baz\"}'::jsonb, " +
                                                           "'<foo>bar</foo><foo>bar</foo>'::xml, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::UUID)";
    protected static final String INSERT_STRING_TYPES_STMT = "INSERT INTO string_table (vc, vcv, ch, c, t, b, bnn, ct) " +
                                                             "VALUES ('\u017E\u0161', 'bb', 'cdef', 'abc', 'some text', E'\\\\000\\\\001\\\\002'::bytea, E'\\\\003\\\\004\\\\005'::bytea, 'Hello World')";
    protected static final String INSERT_NETWORK_ADDRESS_TYPES_STMT = "INSERT INTO network_address_table (i) " +
                                                                      "VALUES ('192.168.2.0/12')";
    protected static final String INSERT_CIDR_NETWORK_ADDRESS_TYPE_STMT = "INSERT INTO cidr_network_address_table (i) " +
                                                                      "VALUES ('192.168.100.128/25');";
    protected static final String INSERT_MACADDR_TYPE_STMT = "INSERT INTO macaddr_table (m) " +
                                                                      "VALUES ('08:00:2b:01:02:03');";
    protected static final String INSERT_MACADDR8_TYPE_STMT = "INSERT INTO macaddr8_table (m) " +
                                                                      "VALUES ('08:00:2b:01:02:03:04:05');";
    protected static final String INSERT_NUMERIC_TYPES_STMT =
            "INSERT INTO numeric_table (si, i, bi, r, db, r_int, db_int, r_nan, db_nan, r_pinf, db_pinf, r_ninf, db_ninf, ss, bs, b) " +
             "VALUES (1, 123456, 1234567890123, 3.3, 4.44, 3, 4, 'NaN', 'NaN', 'Infinity', 'Infinity', '-Infinity', '-Infinity', 1, 123, true)";

    protected static final String INSERT_NUMERIC_DECIMAL_TYPES_STMT =
            "INSERT INTO numeric_decimal_table (d, dzs, dvs, d_nn, n, nzs, nvs, "
                    + "d_int, dzs_int, dvs_int, n_int, nzs_int, nvs_int, "
                    + "d_nan, dzs_nan, dvs_nan, n_nan, nzs_nan, nvs_nan"
                    + ") "
            + "VALUES (1.1, 10.11, 10.1111, 3.30, 22.22, 22.2, 22.2222, "
                    + "1, 10, 10, 22, 22, 22, "
                    + "'NaN', 'NaN', 'NaN', 'NaN', 'NaN', 'NaN'"
            + ")";

    protected static final String INSERT_NUMERIC_DECIMAL_TYPES_STMT_NO_NAN =
            "INSERT INTO numeric_decimal_table (d, dzs, dvs, d_nn, n, nzs, nvs, "
                    + "d_int, dzs_int, dvs_int, n_int, nzs_int, nvs_int, "
                    + "d_nan, dzs_nan, dvs_nan, n_nan, nzs_nan, nvs_nan"
                    + ") "
            + "VALUES (1.1, 10.11, 10.1111, 3.30, 22.22, 22.2, 22.2222, "
                    + "1, 10, 10, 22, 22, 22, "
                    + "null, null, null, null, null, null"
            + ")";

    protected static final String INSERT_RANGE_TYPES_STMT = "INSERT INTO range_table (unbounded_exclusive_tsrange, bounded_inclusive_tsrange, unbounded_exclusive_tstzrange, bounded_inclusive_tstzrange, unbounded_exclusive_daterange, bounded_exclusive_daterange, int4_number_range, numerange, int8_number_range) " +
            "VALUES ('[2019-03-31 15:30:00, infinity)', '[2019-03-31 15:30:00, 2019-04-30 15:30:00]', '[2017-06-05 11:29:12.549426+00,)', '[2017-06-05 11:29:12.549426+00, 2017-06-05 12:34:56.789012+00]', '[2019-03-31, infinity)', '[2019-03-31, 2019-04-30)', '[1000,6000)', '[5.3,6.3)', '[1000000,6000000)')";

    protected static final String INSERT_ARRAY_TYPES_STMT = "INSERT INTO array_table (int_array, bigint_array, text_array, char_array, varchar_array, date_array, numeric_array, varnumeric_array, citext_array, inet_array, cidr_array, macaddr_array, tsrange_array, tstzrange_array, daterange_array, int4range_array, numerange_array, int8range_array) " +
                                                             "VALUES ('{1,2,3}', '{1550166368505037572}', '{\"one\",\"two\",\"three\"}', '{\"cone\",\"ctwo\",\"cthree\"}', '{\"vcone\",\"vctwo\",\"vcthree\"}', '{2016-11-04,2016-11-05,2016-11-06}', '{1.2,3.4,5.6}', '{1.1,2.22,3.333}', '{\"four\",\"five\",\"six\"}', '{\"192.168.2.0/12\",\"192.168.1.1\",\"192.168.0.2/1\"}', '{\"192.168.100.128/25\", \"192.168.0.0/25\", \"192.168.1.0/24\"}', '{\"08:00:2b:01:02:03\", \"08-00-2b-01-02-03\", \"08002b:010203\"}'," +
                                                                "'{\"[2019-03-31 15:30:00, infinity)\", \"[2019-03-31 15:30:00, 2019-04-30 15:30:00]\"}', '{\"[2017-06-05 11:29:12.549426+00,)\", \"[2017-06-05 11:29:12.549426+00, 2017-06-05 12:34:56.789012+00]\"}', '{\"[2019-03-31, infinity)\", \"[2019-03-31, 2019-04-30)\"}', '{\"[1,6)\", \"[1,4)\"}', '{\"[5.3,6.3)\", \"[10.0,20.0)\"}', '{\"[1000000,6000000)\", \"[5000,9000)\"}')";

    protected static final String INSERT_ARRAY_TYPES_WITH_NULL_VALUES_STMT = "INSERT INTO array_table_with_nulls (int_array, bigint_array, text_array, date_array, numeric_array, varnumeric_array, citext_array, inet_array, cidr_array, macaddr_array, tsrange_array, tstzrange_array, daterange_array, int4range_array, numerange_array, int8range_array) " +
            "VALUES (null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)";

    protected static final String INSERT_POSTGIS_TYPES_STMT = "INSERT INTO public.postgis_table (p, ml) " +
            "VALUES ('SRID=3187;POINT(174.9479 -36.7208)'::postgis.geometry, 'MULTILINESTRING((169.1321 -44.7032, 167.8974 -44.6414))'::postgis.geography)";

    protected static final String INSERT_POSTGIS_TYPES_IN_PUBLIC_STMT = "INSERT INTO public.postgis_table (p, ml) " +
            "VALUES ('SRID=3187;POINT(174.9479 -36.7208)'::geometry, 'MULTILINESTRING((169.1321 -44.7032, 167.8974 -44.6414))'::geography)";

    protected static final String INSERT_POSTGIS_ARRAY_TYPES_STMT = "INSERT INTO public.postgis_array_table (ga, gann) " +
            "VALUES (" +
            "ARRAY['GEOMETRYCOLLECTION EMPTY'::postgis.geometry, 'POLYGON((166.51 -46.64, 178.52 -46.64, 178.52 -34.45, 166.51 -34.45, 166.51 -46.64))'::postgis.geometry], " +
            "ARRAY['GEOMETRYCOLLECTION EMPTY'::postgis.geometry, 'POLYGON((166.51 -46.64, 178.52 -46.64, 178.52 -34.45, 166.51 -34.45, 166.51 -46.64))'::postgis.geometry]" +
            ")";

    protected static final String INSERT_POSTGIS_ARRAY_TYPES_IN_PUBLIC_STMT = "INSERT INTO public.postgis_array_table (ga, gann) " +
            "VALUES (" +
            "ARRAY['GEOMETRYCOLLECTION EMPTY'::geometry, 'POLYGON((166.51 -46.64, 178.52 -46.64, 178.52 -34.45, 166.51 -34.45, 166.51 -46.64))'::geometry], " +
            "ARRAY['GEOMETRYCOLLECTION EMPTY'::geometry, 'POLYGON((166.51 -46.64, 178.52 -46.64, 178.52 -34.45, 166.51 -34.45, 166.51 -46.64))'::geometry]" +
            ")";

    protected static final String INSERT_QUOTED_TYPES_STMT = "INSERT INTO \"Quoted_\"\" . Schema\".\"Quoted_\"\" . Table\" (\"Quoted_\"\" . Text_Column\") " +
                                                             "VALUES ('some text')";

    protected static final String INSERT_CUSTOM_TYPES_STMT = "INSERT INTO custom_table (lt, i, n, lt_array) " +
            "VALUES ('Top.Collections.Pictures.Astronomy.Galaxies', '978-0-393-04002-9', NULL, '{\"Ship.Frigate\",\"Ship.Destroyer\"}')";

    protected static final String INSERT_HSTORE_TYPE_STMT = "INSERT INTO hstore_table (hs) VALUES ('\"key\" => \"val\"'::hstore)";

    protected static final String INSERT_HSTORE_TYPE_WITH_MULTIPLE_VALUES_STMT = "INSERT INTO hstore_table_mul (hs, hsarr) VALUES (" +
            "'\"key1\" => \"val1\",\"key2\" => \"val2\",\"key3\" => \"val3\"', " +
            "array['\"key4\" => \"val4\",\"key5\" => NULL'::hstore, '\"key6\" => \"val6\"']" +
            ")";

    protected static final String INSERT_HSTORE_TYPE_WITH_NULL_VALUES_STMT = "INSERT INTO hstore_table_with_null (hs) VALUES ('\"key1\" => \"val1\",\"key2\" => NULL')";

    protected static final String INSERT_HSTORE_TYPE_WITH_SPECIAL_CHAR_STMT = "INSERT INTO hstore_table_with_special (hs) VALUES ('\"key_#1\" => \"val 1\",\"key 2\" =>\" ##123 78\"')";

    protected static final Set<String> ALL_STMTS = new HashSet<>(Arrays.asList(INSERT_NUMERIC_TYPES_STMT, INSERT_NUMERIC_DECIMAL_TYPES_STMT_NO_NAN,
                                                                 INSERT_DATE_TIME_TYPES_STMT, INSERT_BIN_TYPES_STMT, INSERT_GEOM_TYPES_STMT, INSERT_TEXT_TYPES_STMT,
                                                                 INSERT_CASH_TYPES_STMT, INSERT_STRING_TYPES_STMT, INSERT_CIDR_NETWORK_ADDRESS_TYPE_STMT,
                                                                 INSERT_NETWORK_ADDRESS_TYPES_STMT, INSERT_MACADDR_TYPE_STMT,
                                                                 INSERT_ARRAY_TYPES_STMT, INSERT_ARRAY_TYPES_WITH_NULL_VALUES_STMT, INSERT_QUOTED_TYPES_STMT,
                                                                 INSERT_POSTGIS_TYPES_STMT, INSERT_POSTGIS_ARRAY_TYPES_STMT));

    protected List<SchemaAndValueField> schemasAndValuesForNumericType() {
        final List<SchemaAndValueField> fields = new ArrayList<SchemaAndValueField>();

        fields.addAll(Arrays.asList(new SchemaAndValueField("si", SchemaBuilder.OPTIONAL_INT16_SCHEMA, (short) 1),
                             new SchemaAndValueField("i", SchemaBuilder.OPTIONAL_INT32_SCHEMA, 123456),
                             new SchemaAndValueField("bi", SchemaBuilder.OPTIONAL_INT64_SCHEMA, 1234567890123L),
                             new SchemaAndValueField("r", Schema.OPTIONAL_FLOAT32_SCHEMA, 3.3f),
                             new SchemaAndValueField("db", Schema.OPTIONAL_FLOAT64_SCHEMA, 4.44d),
                             new SchemaAndValueField("r_int", Schema.OPTIONAL_FLOAT32_SCHEMA, 3.0f),
                             new SchemaAndValueField("db_int", Schema.OPTIONAL_FLOAT64_SCHEMA, 4.0d),
                             new SchemaAndValueField("ss", Schema.INT16_SCHEMA, (short) 1),
                             new SchemaAndValueField("bs", Schema.INT64_SCHEMA, 123L),
                             new SchemaAndValueField("b", Schema.OPTIONAL_BOOLEAN_SCHEMA, Boolean.TRUE))
                );
        if (!DecoderDifferences.areSpecialFPValuesUnsupported()) {
            fields.addAll(Arrays.asList(
                    new SchemaAndValueField("r_nan", Schema.OPTIONAL_FLOAT32_SCHEMA, Float.NaN),
                    new SchemaAndValueField("db_nan", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NaN),
                    new SchemaAndValueField("r_pinf", Schema.OPTIONAL_FLOAT32_SCHEMA, Float.POSITIVE_INFINITY),
                    new SchemaAndValueField("db_pinf", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.POSITIVE_INFINITY),
                    new SchemaAndValueField("r_ninf", Schema.OPTIONAL_FLOAT32_SCHEMA, Float.NEGATIVE_INFINITY),
                    new SchemaAndValueField("db_ninf", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NEGATIVE_INFINITY)
            ));
        }
        return fields;
    }

    protected List<SchemaAndValueField> schemasAndValuesForBigDecimalEncodedNumericTypes() {
        final Struct dvs = new Struct(VariableScaleDecimal.schema());
        dvs.put("scale", 4).put("value", new BigDecimal("10.1111").unscaledValue().toByteArray());
        final Struct nvs = new Struct(VariableScaleDecimal.schema());
        nvs.put("scale", 4).put("value", new BigDecimal("22.2222").unscaledValue().toByteArray());
        final Struct dvs_int = new Struct(VariableScaleDecimal.schema());
        dvs_int.put("scale", 0).put("value", new BigDecimal("10").unscaledValue().toByteArray());
        final Struct nvs_int = new Struct(VariableScaleDecimal.schema());
        nvs_int.put("scale", 0).put("value", new BigDecimal("22").unscaledValue().toByteArray());
        final List<SchemaAndValueField> fields = new ArrayList<SchemaAndValueField>(Arrays.asList(
                new SchemaAndValueField("d", Decimal.builder(2).parameter(TestHelper.PRECISION_PARAMETER_KEY, "3").optional().build(), new BigDecimal("1.10")),
                new SchemaAndValueField("dzs", Decimal.builder(0).parameter(TestHelper.PRECISION_PARAMETER_KEY, "4").optional().build(), new BigDecimal("10")),
                new SchemaAndValueField("dvs", VariableScaleDecimal.optionalSchema(), dvs),
                new SchemaAndValueField("d_nn", Decimal.builder(2).parameter(TestHelper.PRECISION_PARAMETER_KEY, "3").build(), new BigDecimal("3.30")),
                new SchemaAndValueField("n", Decimal.builder(4).parameter(TestHelper.PRECISION_PARAMETER_KEY, "6").optional().build(), new BigDecimal("22.2200")),
                new SchemaAndValueField("nzs", Decimal.builder(0).parameter(TestHelper.PRECISION_PARAMETER_KEY, "4").optional().build(), new BigDecimal("22")),
                new SchemaAndValueField("nvs", VariableScaleDecimal.optionalSchema(), nvs),
                new SchemaAndValueField("d_int", Decimal.builder(2).parameter(TestHelper.PRECISION_PARAMETER_KEY, "3").optional().build(), new BigDecimal("1.00")),
                new SchemaAndValueField("dvs_int", VariableScaleDecimal.optionalSchema(), dvs_int),
                new SchemaAndValueField("n_int", Decimal.builder(4).parameter(TestHelper.PRECISION_PARAMETER_KEY, "6").optional().build(), new BigDecimal("22.0000")),
                new SchemaAndValueField("nvs_int", VariableScaleDecimal.optionalSchema(), nvs_int)
        ));
        return fields;
    }

    protected List<SchemaAndValueField> schemasAndValuesForStringEncodedNumericTypes() {
        final List<SchemaAndValueField> fields = new ArrayList<SchemaAndValueField>(Arrays.asList(
                new SchemaAndValueField("d", Schema.OPTIONAL_STRING_SCHEMA, "1.10"),
                new SchemaAndValueField("dzs", Schema.OPTIONAL_STRING_SCHEMA, "10"),
                new SchemaAndValueField("dvs", Schema.OPTIONAL_STRING_SCHEMA, "10.1111"),
                new SchemaAndValueField("n", Schema.OPTIONAL_STRING_SCHEMA, "22.2200"),
                new SchemaAndValueField("nzs", Schema.OPTIONAL_STRING_SCHEMA, "22"),
                new SchemaAndValueField("nvs", Schema.OPTIONAL_STRING_SCHEMA, "22.2222"),
                new SchemaAndValueField("d_int", Schema.OPTIONAL_STRING_SCHEMA, "1.00"),
                new SchemaAndValueField("dzs_int", Schema.OPTIONAL_STRING_SCHEMA, "10"),
                new SchemaAndValueField("dvs_int", Schema.OPTIONAL_STRING_SCHEMA, "10"),
                new SchemaAndValueField("n_int", Schema.OPTIONAL_STRING_SCHEMA, "22.0000"),
                new SchemaAndValueField("nzs_int", Schema.OPTIONAL_STRING_SCHEMA, "22"),
                new SchemaAndValueField("nvs_int", Schema.OPTIONAL_STRING_SCHEMA, "22")
        ));
        if (!DecoderDifferences.areSpecialFPValuesUnsupported()) {
            fields.addAll(Arrays.asList(
                    new SchemaAndValueField("d_nan", Schema.OPTIONAL_STRING_SCHEMA, "NAN"),
                    new SchemaAndValueField("dzs_nan", Schema.OPTIONAL_STRING_SCHEMA, "NAN"),
                    new SchemaAndValueField("dvs_nan", Schema.OPTIONAL_STRING_SCHEMA, "NAN"),
                    new SchemaAndValueField("n_nan", Schema.OPTIONAL_STRING_SCHEMA, "NAN"),
                    new SchemaAndValueField("nzs_nan", Schema.OPTIONAL_STRING_SCHEMA, "NAN"),
                    new SchemaAndValueField("nvs_nan", Schema.OPTIONAL_STRING_SCHEMA, "NAN")
            ));
        }
        return fields;
    }

    protected List<SchemaAndValueField> schemasAndValuesForDoubleEncodedNumericTypes() {
        final List<SchemaAndValueField> fields = new ArrayList<SchemaAndValueField>(Arrays.asList(
                new SchemaAndValueField("d", Schema.OPTIONAL_FLOAT64_SCHEMA, 1.1d),
                new SchemaAndValueField("dzs", Schema.OPTIONAL_FLOAT64_SCHEMA, 10d),
                new SchemaAndValueField("dvs", Schema.OPTIONAL_FLOAT64_SCHEMA, 10.1111d),
                new SchemaAndValueField("n", Schema.OPTIONAL_FLOAT64_SCHEMA, 22.22d),
                new SchemaAndValueField("nzs", Schema.OPTIONAL_FLOAT64_SCHEMA, 22d),
                new SchemaAndValueField("nvs", Schema.OPTIONAL_FLOAT64_SCHEMA, 22.2222d)
        ));
        if (!DecoderDifferences.areSpecialFPValuesUnsupported()) {
            fields.addAll(Arrays.asList(
                    new SchemaAndValueField("d_nan", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NaN),
                    new SchemaAndValueField("dzs_nan", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NaN),
                    new SchemaAndValueField("dvs_nan", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NaN),
                    new SchemaAndValueField("n_nan", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NaN),
                    new SchemaAndValueField("nzs_nan", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NaN),
                    new SchemaAndValueField("nvs_nan", Schema.OPTIONAL_FLOAT64_SCHEMA, Double.NaN)
            ));
        }
        return fields;
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForMapEncodedHStoreType(){
         final Map<String, String> expected = new HashMap<>();
         expected.put("key", "val");
        return Arrays.asList(new SchemaAndValueField("hs", hstoreMapSchema(), expected));
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForMapEncodedHStoreTypeWithMultipleValues(){
        final Map<String, String> expected = new HashMap<>();
        expected.put("key1", "val1");
        expected.put("key2", "val2");
        expected.put("key3", "val3");

        Map<String, String> expectedArray1 = new HashMap<>();
        expectedArray1.put("key4", "val4");
        expectedArray1.put("key5", null);

        Map<String, String> expectedArray2 = new HashMap<>();
        expectedArray2.put("key6", "val6");

        return Arrays.asList(
                new SchemaAndValueField("hs", hstoreMapSchema(), expected),
                new SchemaAndValueField("hsarr", SchemaBuilder.array(hstoreMapSchema()).optional().build(), Arrays.asList(expectedArray1, expectedArray2))
        );
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForMapEncodedHStoreTypeWithNullValues(){
        final Map<String, String> expected = new HashMap<>();
        expected.put("key1", "val1");
        expected.put("key2", null);
        return Arrays.asList(new SchemaAndValueField("hs", hstoreMapSchema(), expected));
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForMapEncodedHStoreTypeWithSpecialCharacters(){
        final Map<String, String> expected = new HashMap<>();
        expected.put("key_#1", "val 1");
        expected.put("key 2", " ##123 78");
        return Arrays.asList(new SchemaAndValueField("hs", hstoreMapSchema(), expected));
    }

    private Schema hstoreMapSchema() {
        return SchemaBuilder.map(
                Schema.STRING_SCHEMA,
                SchemaBuilder.string().optional().build()
                )
                .optional()
                .build();
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForJsonEncodedHStoreType(){
        final String expected = "{\"key\":\"val\"}";
        return Arrays.asList(new SchemaAndValueField("hs", Json.builder().optional().build(), expected));
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForJsonEncodedHStoreTypeWithMultipleValues(){
        final String expected = "{\"key1\":\"val1\",\"key2\":\"val2\",\"key3\":\"val3\"}";
        final List<String> expectedArray = Arrays.asList(
                "{\"key5\":null,\"key4\":\"val4\"}",
                "{\"key6\":\"val6\"}"
        );

        return Arrays.asList(
                new SchemaAndValueField("hs", Json.builder().optional().build(), expected),
                new SchemaAndValueField("hsarr", SchemaBuilder.array(Json.builder().optional().build()).optional().build(), expectedArray)
        );
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForJsonEncodedHStoreTypeWithNullValues(){
        final String expected = "{\"key1\":\"val1\",\"key2\":null}";
        return Arrays.asList(new SchemaAndValueField("hs", Json.builder().optional().build(), expected));
    }

    protected List<SchemaAndValueField> schemaAndValueFieldForJsonEncodedHStoreTypeWithSpcialCharacters(){
        final String expected = "{\"key_#1\":\"val 1\",\"key 2\":\" ##123 78\"}";
        return Arrays.asList(new SchemaAndValueField("hs", Json.builder().optional().build(), expected));
    }

    protected List<SchemaAndValueField> schemaAndValueForMacaddr8Type() {
        final String expected = "08:00:2b:01:02:03:04:05";
        return Arrays.asList(new SchemaAndValueField("m", Schema.OPTIONAL_STRING_SCHEMA, expected));
    }

    protected List<SchemaAndValueField> schemasAndValuesForStringTypes() {
       return Arrays.asList(new SchemaAndValueField("vc", Schema.OPTIONAL_STRING_SCHEMA, "\u017E\u0161"),
                            new SchemaAndValueField("vcv", Schema.OPTIONAL_STRING_SCHEMA, "bb"),
                            new SchemaAndValueField("ch", Schema.OPTIONAL_STRING_SCHEMA, "cdef"),
                            new SchemaAndValueField("c", Schema.OPTIONAL_STRING_SCHEMA, "abc"),
                            new SchemaAndValueField("t", Schema.OPTIONAL_STRING_SCHEMA, "some text"),
                            new SchemaAndValueField("b", Schema.OPTIONAL_BYTES_SCHEMA, ByteBuffer.wrap(new byte[] {0, 1, 2})),
                            new SchemaAndValueField("bnn", Schema.BYTES_SCHEMA, ByteBuffer.wrap(new byte[] {3, 4, 5})),
                            new SchemaAndValueField("ct", Schema.OPTIONAL_STRING_SCHEMA, "Hello World")
               );
    }

    protected List<SchemaAndValueField> schemasAndValuesForStringTypesWithSourceColumnTypeInfo() {
        return Arrays.asList(new SchemaAndValueField("vc",
                                    SchemaBuilder.string().optional()
                                        .parameter(TestHelper.TYPE_NAME_PARAMETER_KEY, "VARCHAR")
                                        .parameter(TestHelper.TYPE_LENGTH_PARAMETER_KEY, "2")
                                        .parameter(TestHelper.TYPE_SCALE_PARAMETER_KEY, "0")
                                        .build(),
                                    "\u017E\u0161"
                             ),
                             new SchemaAndValueField("vcv",
                                     SchemaBuilder.string().optional()
                                         .parameter(TestHelper.TYPE_NAME_PARAMETER_KEY, "VARCHAR")
                                         .parameter(TestHelper.TYPE_LENGTH_PARAMETER_KEY, "2")
                                         .parameter(TestHelper.TYPE_SCALE_PARAMETER_KEY, "0")
                                         .build(),
                                     "bb"
                             ),
                             new SchemaAndValueField("ch", Schema.OPTIONAL_STRING_SCHEMA, "cdef"),
                             new SchemaAndValueField("c", Schema.OPTIONAL_STRING_SCHEMA, "abc"),
                             new SchemaAndValueField("t", Schema.OPTIONAL_STRING_SCHEMA, "some text"),
                             new SchemaAndValueField("b", Schema.OPTIONAL_BYTES_SCHEMA, ByteBuffer.wrap(new byte[] {0, 1, 2})),
                             new SchemaAndValueField("bnn", Schema.BYTES_SCHEMA, ByteBuffer.wrap(new byte[] {3, 4, 5}))
                );
     }

    protected List<SchemaAndValueField> schemasAndValuesForNetworkAddressTypes() {
        return Arrays.asList(new SchemaAndValueField("i", Schema.OPTIONAL_STRING_SCHEMA, "192.168.2.0/12"));
    }

    protected List<SchemaAndValueField> schemasAndValueForCidrAddressType() {
        return Arrays.asList(new SchemaAndValueField("i", Schema.OPTIONAL_STRING_SCHEMA, "192.168.100.128/25"));
    }

    protected List<SchemaAndValueField> schemasAndValueForMacaddrType() {
        return Arrays.asList(new SchemaAndValueField("m", Schema.OPTIONAL_STRING_SCHEMA, "08:00:2b:01:02:03"));
    }

    protected List<SchemaAndValueField> schemasAndValuesForNumericTypesWithSourceColumnTypeInfo() {
        return Arrays.asList(new SchemaAndValueField("d",
                SchemaBuilder.float64().optional()
                    .parameter(TestHelper.TYPE_NAME_PARAMETER_KEY, "NUMERIC")
                    .parameter(TestHelper.TYPE_LENGTH_PARAMETER_KEY, "3")
                    .parameter(TestHelper.TYPE_SCALE_PARAMETER_KEY, "2")
                    .build(),
                1.1d
            ),
            new SchemaAndValueField("dzs",
                SchemaBuilder.float64().optional()
                    .parameter(TestHelper.TYPE_NAME_PARAMETER_KEY, "NUMERIC")
                    .parameter(TestHelper.TYPE_LENGTH_PARAMETER_KEY, "4")
                    .parameter(TestHelper.TYPE_SCALE_PARAMETER_KEY, "0")
                    .build(),
                10d
            ),
            new SchemaAndValueField("dvs", Schema.OPTIONAL_FLOAT64_SCHEMA, 10.1111d),
            new SchemaAndValueField("n", Schema.OPTIONAL_FLOAT64_SCHEMA, 22.22d),
            new SchemaAndValueField("nzs", Schema.OPTIONAL_FLOAT64_SCHEMA, 22d),
            new SchemaAndValueField("nvs", Schema.OPTIONAL_FLOAT64_SCHEMA, 22.2222d)
        );
    }

    protected List<SchemaAndValueField> schemasAndValuesForTextTypes() {
        return Arrays.asList(new SchemaAndValueField("j", Json.builder().optional().build(), "{\"bar\": \"baz\"}"),
                             new SchemaAndValueField("jb", Json.builder().optional().build(), "{\"bar\": \"baz\"}"),
                             new SchemaAndValueField("x", Xml.builder().optional().build(), "<foo>bar</foo><foo>bar</foo>"),
                             new SchemaAndValueField("u", Uuid.builder().optional().build(), "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"));
    }


    protected List<SchemaAndValueField> schemaAndValuesForGeomTypes() {
        Schema pointSchema = Point.builder().optional().build();
        return Collections.singletonList(new SchemaAndValueField("p", pointSchema, Point.createValue(pointSchema, 1, 1)));
    }

    protected List<SchemaAndValueField> schemaAndValuesForRangeTypes() {
        String unboundedEnd = "infinity";

        // Tstrange type
        String beginTsrange = "2019-03-31 15:30:00";
        String endTsrange = "2019-04-30 15:30:00";

        String expectedUnboundedExclusiveTsrange = String.format("[\"%s\",%s)", beginTsrange, unboundedEnd);
        String expectedBoundedInclusiveTsrange = String.format("[\"%s\",\"%s\"]", beginTsrange, endTsrange);

        // Tstzrange type
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSx");
        Instant beginTstzrange = dateTimeFormatter.parse("2017-06-05 11:29:12.549426+00", Instant::from);
        Instant endTstzrange = dateTimeFormatter.parse("2017-06-05 12:34:56.789012+00", Instant::from);

        // Acknowledge timezone expectation of the system running the test
        String beginSystemTime = dateTimeFormatter.withZone(ZoneId.systemDefault()).format(beginTstzrange);
        String endSystemTime = dateTimeFormatter.withZone(ZoneId.systemDefault()).format(endTstzrange);

        String expectedUnboundedExclusiveTstzrange = String.format("[\"%s\",)", beginSystemTime);
        String expectedBoundedInclusiveTstzrange = String.format("[\"%s\",\"%s\"]", beginSystemTime, endSystemTime);

        // Daterange
        String beginDaterange = "2019-03-31";
        String endDaterange = "2019-04-30";

        String expectedUnboundedDaterange = String.format("[%s,%s)", beginDaterange, unboundedEnd);
        String expectedBoundedDaterange = String.format("[%s,%s)", beginDaterange, endDaterange);

         //int4range
        String beginrange = "1000";
        String endrange = "6000";

        String expectedrange = String.format("[%s,%s)", beginrange, endrange);

        // numrange
        String beginnumrange = "5.3";
        String endnumrange = "6.3";

        String expectednumrange = String.format("[%s,%s)", beginnumrange, endnumrange);

        // int8range
        String beginint8range = "1000000";
        String endint8range = "6000000";

        String expectedint8range = String.format("[%s,%s)", beginint8range, endint8range);



        return Arrays.asList(
                new SchemaAndValueField("unbounded_exclusive_tsrange", Schema.OPTIONAL_STRING_SCHEMA, expectedUnboundedExclusiveTsrange),
                new SchemaAndValueField("bounded_inclusive_tsrange", Schema.OPTIONAL_STRING_SCHEMA, expectedBoundedInclusiveTsrange),
                new SchemaAndValueField("unbounded_exclusive_tstzrange", Schema.OPTIONAL_STRING_SCHEMA, expectedUnboundedExclusiveTstzrange),
                new SchemaAndValueField("bounded_inclusive_tstzrange", Schema.OPTIONAL_STRING_SCHEMA, expectedBoundedInclusiveTstzrange),
                new SchemaAndValueField("unbounded_exclusive_daterange", Schema.OPTIONAL_STRING_SCHEMA, expectedUnboundedDaterange),
                new SchemaAndValueField("bounded_exclusive_daterange", Schema.OPTIONAL_STRING_SCHEMA, expectedBoundedDaterange),
                new SchemaAndValueField("int4_number_range", Schema.OPTIONAL_STRING_SCHEMA, expectedrange),
                new SchemaAndValueField("numerange", Schema.OPTIONAL_STRING_SCHEMA, expectednumrange),
                new SchemaAndValueField("int8_number_range", Schema.OPTIONAL_STRING_SCHEMA, expectedint8range)
                );
    }

    protected List<SchemaAndValueField> schemaAndValuesForBinTypes() {
       return Arrays.asList(new SchemaAndValueField("ba", Schema.OPTIONAL_BYTES_SCHEMA, ByteBuffer.wrap(new byte[]{ 1, 2, 3})),
                            new SchemaAndValueField("bol", Schema.OPTIONAL_BOOLEAN_SCHEMA, false),
                            new SchemaAndValueField("bs", Bits.builder(2).optional().build(), new byte[] { 3, 0 }),  // bitsets get converted from two's complement
                            new SchemaAndValueField("bv", Bits.builder(2).optional().build(), new byte[] { 0, 0 }));
    }

    protected List<SchemaAndValueField> schemaAndValuesForDateTimeTypes() {
        long expectedTs = MicroTimestamp.toEpochMicros(LocalDateTime.parse("2016-11-04T13:51:30.123456"), null);
        long expectedTsMs = Timestamp.toEpochMillis(LocalDateTime.parse("2016-11-04T13:51:30.123456"), null);
        long expectedNegTs = MicroTimestamp.toEpochMicros(LocalDateTime.parse("1936-10-25T22:10:12.608"), null);
        String expectedTz = "2016-11-04T11:51:30.123456Z"; //timestamp is stored with TZ, should be read back with UTC
        int expectedDate = Date.toEpochDay(LocalDate.parse("2016-11-04"), null);
        long expectedTi = LocalTime.parse("13:51:30").toNanoOfDay() / 1_000;
        long expectedTiPrecision = LocalTime.parse("13:51:30.123").toNanoOfDay() / 1_000_000;
        long expectedTtf = TimeUnit.DAYS.toNanos(1) / 1_000;
        String expectedTtz = "11:51:30.123789Z";  //time is stored with TZ, should be read back at GMT
        String expectedTtzPrecision = "11:51:30.123Z";
        double interval = MicroDuration.durationMicros(1, 2, 3, 4, 5, 0, MicroDuration.DAYS_PER_MONTH_AVG);

        return Arrays.asList(new SchemaAndValueField("ts", MicroTimestamp.builder().optional().build(), expectedTs),
                             new SchemaAndValueField("tsneg", MicroTimestamp.builder().optional().build(), expectedNegTs),
                             new SchemaAndValueField("ts_ms", Timestamp.builder().optional().build(), expectedTsMs),
                             new SchemaAndValueField("ts_us", MicroTimestamp.builder().optional().build(), expectedTs),
                             new SchemaAndValueField("tz", ZonedTimestamp.builder().optional().build(), expectedTz),
                             new SchemaAndValueField("date", Date.builder().optional().build(), expectedDate),
                             new SchemaAndValueField("ti", MicroTime.builder().optional().build(), expectedTi),
                             new SchemaAndValueField("tip", Time.builder().optional().build(), (int) expectedTiPrecision),
                             new SchemaAndValueField("ttf", MicroTime.builder().optional().build(), expectedTtf),
                             new SchemaAndValueField("ttz", ZonedTime.builder().optional().build(), expectedTtz),
                             new SchemaAndValueField("tptz", ZonedTime.builder().optional().build(), expectedTtzPrecision),
                             new SchemaAndValueField("it", MicroDuration.builder().optional().build(), interval));
    }

    protected List<SchemaAndValueField> schemaAndValuesForDateTimeTypesAdaptiveTimeMicroseconds() {
        long expectedTs = MicroTimestamp.toEpochMicros(LocalDateTime.parse("2016-11-04T13:51:30.123456"), null);
        long expectedTsMs = Timestamp.toEpochMillis(LocalDateTime.parse("2016-11-04T13:51:30.123456"), null);
        long expectedNegTs = MicroTimestamp.toEpochMicros(LocalDateTime.parse("1936-10-25T22:10:12.608"), null);
        String expectedTz = "2016-11-04T11:51:30.123456Z"; //timestamp is stored with TZ, should be read back with UTC
        int expectedDate = Date.toEpochDay(LocalDate.parse("2016-11-04"), null);
        long expectedTi = LocalTime.parse("13:51:30").toNanoOfDay() / 1_000;
        String expectedTtz = "11:51:30.123789Z";  //time is stored with TZ, should be read back at GMT
        double interval = MicroDuration.durationMicros(1, 2, 3, 4, 5, 0, MicroDuration.DAYS_PER_MONTH_AVG);

        return Arrays.asList(new SchemaAndValueField("ts", MicroTimestamp.builder().optional().build(), expectedTs),
                new SchemaAndValueField("tsneg", MicroTimestamp.builder().optional().build(), expectedNegTs),
                new SchemaAndValueField("ts_ms", Timestamp.builder().optional().build(), expectedTsMs),
                new SchemaAndValueField("ts_us", MicroTimestamp.builder().optional().build(), expectedTs),
                new SchemaAndValueField("tz", ZonedTimestamp.builder().optional().build(), expectedTz),
                new SchemaAndValueField("date", Date.builder().optional().build(), expectedDate),
                new SchemaAndValueField("ti", MicroTime.builder().optional().build(), expectedTi),
                new SchemaAndValueField("ttz", ZonedTime.builder().optional().build(), expectedTtz),
                new SchemaAndValueField("it", MicroDuration.builder().optional().build(), interval));
    }

    protected List<SchemaAndValueField> schemaAndValuesForMoneyTypes() {
        return Collections.singletonList(new SchemaAndValueField("csh", Decimal.builder(2).optional().build(),
                                                                 BigDecimal.valueOf(1234.11d)));
    }

    protected List<SchemaAndValueField> schemasAndValuesForArrayTypes() {
        Struct element;
        final List<Struct> varnumArray = new ArrayList<>();
        element = new Struct(VariableScaleDecimal.schema());
        element.put("scale", 1).put("value", new BigDecimal("1.1").unscaledValue().toByteArray());
        varnumArray.add(element);
        element = new Struct(VariableScaleDecimal.schema());
        element.put("scale", 2).put("value", new BigDecimal("2.22").unscaledValue().toByteArray());
        varnumArray.add(element);
        element = new Struct(VariableScaleDecimal.schema());
        element.put("scale", 3).put("value", new BigDecimal("3.333").unscaledValue().toByteArray());
        varnumArray.add(element);

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSx");
        Instant begin = dateTimeFormatter.parse("2017-06-05 11:29:12.549426+00", Instant::from);
        Instant end = dateTimeFormatter.parse("2017-06-05 12:34:56.789012+00", Instant::from);

        // Acknowledge timezone expectation of the system running the test
        String beginSystemTime = dateTimeFormatter.withZone(ZoneId.systemDefault()).format(begin);
        String endSystemTime = dateTimeFormatter.withZone(ZoneId.systemDefault()).format(end);

        String expectedFirstTstzrange = String.format("[\"%s\",)", beginSystemTime);
        String expectedSecondTstzrange = String.format("[\"%s\",\"%s\"]", beginSystemTime, endSystemTime);

       return Arrays.asList(new SchemaAndValueField("int_array", SchemaBuilder.array(Schema.OPTIONAL_INT32_SCHEMA).optional().build(),
                                Arrays.asList(1, 2, 3)),
                            new SchemaAndValueField("bigint_array", SchemaBuilder.array(Schema.OPTIONAL_INT64_SCHEMA).optional().build(),
                                Arrays.asList(1550166368505037572L)),
                            new SchemaAndValueField("text_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(),
                                Arrays.asList("one", "two", "three")),
                            new SchemaAndValueField("char_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(),
                                Arrays.asList("cone      ", "ctwo      ", "cthree    ")),
                            new SchemaAndValueField("varchar_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(),
                                Arrays.asList("vcone", "vctwo", "vcthree")),
                            new SchemaAndValueField("date_array", SchemaBuilder.array(Date.builder().optional().schema()).optional().build(),
                                Arrays.asList(
                                        (int) LocalDate.of(2016, Month.NOVEMBER, 4).toEpochDay(),
                                        (int) LocalDate.of(2016, Month.NOVEMBER, 5).toEpochDay(),
                                        (int) LocalDate.of(2016, Month.NOVEMBER, 6).toEpochDay()
                                )),
                            new SchemaAndValueField("numeric_array", SchemaBuilder.array(Decimal.builder(2).parameter(TestHelper.PRECISION_PARAMETER_KEY, "10").optional().build()).optional().build(),
                                    Arrays.asList(
                                            new BigDecimal("1.20"),
                                            new BigDecimal("3.40"),
                                            new BigDecimal("5.60")
                                    )),
                            new SchemaAndValueField("varnumeric_array", SchemaBuilder.array(VariableScaleDecimal.builder().optional().build()).optional().build(),
                                    varnumArray),
                            new SchemaAndValueField("citext_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("four", "five", "six")),
                            new SchemaAndValueField("inet_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("192.168.2.0/12", "192.168.1.1", "192.168.0.2/1")),
                           new SchemaAndValueField("cidr_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("192.168.100.128/25", "192.168.0.0/25", "192.168.1.0/24")),
                            new SchemaAndValueField("macaddr_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("08:00:2b:01:02:03", "08:00:2b:01:02:03", "08:00:2b:01:02:03")),
                            new SchemaAndValueField("tsrange_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("[\"2019-03-31 15:30:00\",infinity)", "[\"2019-03-31 15:30:00\",\"2019-04-30 15:30:00\"]")),
                            new SchemaAndValueField("tstzrange_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList(expectedFirstTstzrange, expectedSecondTstzrange)),
                            new SchemaAndValueField("daterange_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("[2019-03-31,infinity)", "[2019-03-31,2019-04-30)")),
                            new SchemaAndValueField("int4range_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("[1,6)", "[1,4)")),
                            new  SchemaAndValueField("numerange_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("[5.3,6.3)", "[10.0,20.0)")),
                            new  SchemaAndValueField("int8range_array", SchemaBuilder.array(SchemaBuilder.OPTIONAL_STRING_SCHEMA).optional().build(),
                                    Arrays.asList("[1000000,6000000)", "[5000,9000)"))
                            );
    }

    protected List<SchemaAndValueField> schemasAndValuesForArrayTypesWithNullValues() {
        return Arrays.asList(
                new SchemaAndValueField("int_array", SchemaBuilder.array(Schema.OPTIONAL_INT32_SCHEMA).optional().build(), null),
                new SchemaAndValueField("bigint_array", SchemaBuilder.array(Schema.OPTIONAL_INT64_SCHEMA).optional().build(), null),
                new SchemaAndValueField("text_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("char_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("varchar_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("date_array", SchemaBuilder.array(Date.builder().optional().schema()).optional().build(), null),
                new SchemaAndValueField("numeric_array", SchemaBuilder.array(Decimal.builder(2).parameter(TestHelper.PRECISION_PARAMETER_KEY, "10").optional().build()).optional().build(), null),
                new SchemaAndValueField("citext_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("inet_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("cidr_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("macaddr_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("tsrange_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("tstzrange_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("daterange_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("int4range_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("numerange_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null),
                new SchemaAndValueField("int8range_array", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build(), null)
        );
    }

    protected List<SchemaAndValueField> schemaAndValuesForPostgisTypes() {
        Schema geomSchema = Geometry.builder().optional().build();
        Schema geogSchema = Geography.builder().optional().build();
        return Arrays.asList(
                // geometries are encoded here as HexEWKB
                new SchemaAndValueField("p", geomSchema,
                        // 'SRID=3187;POINT(174.9479 -36.7208)'::postgis.geometry
                        Geometry.createValue(geomSchema, DatatypeConverter.parseHexBinary("0101000020730C00001C7C613255DE6540787AA52C435C42C0"), 3187)),
                new SchemaAndValueField("ml", geogSchema,
                        // 'MULTILINESTRING((169.1321 -44.7032, 167.8974 -44.6414))'::postgis.geography
                        Geography.createValue(geogSchema, DatatypeConverter.parseHexBinary("0105000020E610000001000000010200000002000000A779C7293A2465400B462575025A46C0C66D3480B7FC6440C3D32B65195246C0"), 4326))
        );
    }

    protected List<SchemaAndValueField> schemaAndValuesForPostgisArrayTypes() {
        Schema geomSchema = Geometry.builder()
                .optional()
                .build();

        List<Struct> values = Arrays.asList(
                // 'GEOMETRYCOLLECTION EMPTY'::postgis.geometry
                Geometry.createValue(geomSchema, DatatypeConverter.parseHexBinary("010700000000000000"), null),
                // 'POLYGON((166.51 -46.64, 178.52 -46.64, 178.52 -34.45, 166.51 -34.45, 166.51 -46.64))'::postgis.geometry
                Geometry.createValue(geomSchema, DatatypeConverter.parseHexBinary("01030000000100000005000000B81E85EB51D0644052B81E85EB5147C0713D0AD7A350664052B81E85EB5147C0713D0AD7A35066409A999999993941C0B81E85EB51D064409A999999993941C0B81E85EB51D0644052B81E85EB5147C0"), null)
        );
        return Arrays.asList(
                // geometries are encoded here as HexEWKB
                new SchemaAndValueField("ga", SchemaBuilder.array(geomSchema).optional().build(), values ),
                new SchemaAndValueField("gann", SchemaBuilder.array(geomSchema).build(), values )
        );
    }

    protected List<SchemaAndValueField> schemasAndValuesForQuotedTypes() {
       return Arrays.asList(new SchemaAndValueField("Quoted_\" . Text_Column", Schema.OPTIONAL_STRING_SCHEMA, "some text"));
    }

    protected Map<String, List<SchemaAndValueField>> schemaAndValuesByTopicName() {
        return ALL_STMTS.stream().collect(Collectors.toMap(AbstractRecordsProducerTest::topicNameFromInsertStmt,
                                                           this::schemasAndValuesForTable));
    }

    protected Map<String, List<SchemaAndValueField>> schemaAndValuesByTopicNameAdaptiveTimeMicroseconds() {
        return ALL_STMTS.stream().collect(Collectors.toMap(AbstractRecordsProducerTest::topicNameFromInsertStmt,
                this::schemasAndValuesForTableAdaptiveTimeMicroseconds));
    }

    protected Map<String, List<SchemaAndValueField>> schemaAndValuesByTopicNameStringEncodedDecimals() {
        return ALL_STMTS.stream().collect(Collectors.toMap(AbstractRecordsProducerTest::topicNameFromInsertStmt,
                this::schemasAndValuesForNumericTypesUsingStringEncoding));
    }

    protected List<SchemaAndValueField> schemasAndValuesForTableAdaptiveTimeMicroseconds(String insertTableStatement) {
        if (insertTableStatement.equals(INSERT_DATE_TIME_TYPES_STMT)) {
            return schemaAndValuesForDateTimeTypesAdaptiveTimeMicroseconds();
        }
        return schemasAndValuesForTable(insertTableStatement);
    }

    protected List<SchemaAndValueField> schemasAndValuesForNumericTypesUsingStringEncoding(String insertTableStatement) {
        if (insertTableStatement.equals(INSERT_NUMERIC_DECIMAL_TYPES_STMT_NO_NAN)) {
            return schemasAndValuesForStringEncodedNumericTypes();
        }
        return schemasAndValuesForTable(insertTableStatement);
    }

    protected List<SchemaAndValueField> schemasAndValuesForCustomTypes() {
        return Arrays.asList(new SchemaAndValueField("lt", Schema.OPTIONAL_BYTES_SCHEMA, ByteBuffer.wrap("Top.Collections.Pictures.Astronomy.Galaxies".getBytes())),
                             new SchemaAndValueField("i", Schema.BYTES_SCHEMA, ByteBuffer.wrap("0-393-04002-X".getBytes())),
                             new SchemaAndValueField("n", Schema.OPTIONAL_STRING_SCHEMA, null),
                             new SchemaAndValueField("lt_array", Schema.OPTIONAL_BYTES_SCHEMA, ByteBuffer.wrap("{Ship.Frigate,Ship.Destroyer}".getBytes())));

    }

    protected List<SchemaAndValueField> schemasAndValuesForTable(String insertTableStatement) {
        switch (insertTableStatement) {
            case INSERT_NUMERIC_TYPES_STMT:
                return schemasAndValuesForNumericType();
            case INSERT_NUMERIC_DECIMAL_TYPES_STMT_NO_NAN:
                return schemasAndValuesForBigDecimalEncodedNumericTypes();
            case INSERT_BIN_TYPES_STMT:
                return schemaAndValuesForBinTypes();
            case INSERT_CASH_TYPES_STMT:
                return schemaAndValuesForMoneyTypes();
            case INSERT_DATE_TIME_TYPES_STMT:
                return schemaAndValuesForDateTimeTypes();
            case INSERT_GEOM_TYPES_STMT:
                return schemaAndValuesForGeomTypes();
            case INSERT_STRING_TYPES_STMT:
                return schemasAndValuesForStringTypes();
            case INSERT_NETWORK_ADDRESS_TYPES_STMT:
                return schemasAndValuesForNetworkAddressTypes();
            case INSERT_CIDR_NETWORK_ADDRESS_TYPE_STMT:
                return schemasAndValueForCidrAddressType();
            case INSERT_MACADDR_TYPE_STMT:
                return schemasAndValueForMacaddrType();
            case INSERT_RANGE_TYPES_STMT:
                return schemaAndValuesForRangeTypes();
            case INSERT_TEXT_TYPES_STMT:
                return schemasAndValuesForTextTypes();
            case INSERT_ARRAY_TYPES_STMT:
                return schemasAndValuesForArrayTypes();
            case INSERT_ARRAY_TYPES_WITH_NULL_VALUES_STMT:
                return schemasAndValuesForArrayTypesWithNullValues();
            case INSERT_CUSTOM_TYPES_STMT:
                return schemasAndValuesForCustomTypes();
            case INSERT_POSTGIS_TYPES_STMT:
                return schemaAndValuesForPostgisTypes();
            case INSERT_POSTGIS_ARRAY_TYPES_STMT:
                return schemaAndValuesForPostgisArrayTypes();
            case INSERT_QUOTED_TYPES_STMT:
                return schemasAndValuesForQuotedTypes();
            default:
                throw new IllegalArgumentException("unknown statement:" + insertTableStatement);
        }
    }

    protected void assertRecordSchemaAndValues(List<SchemaAndValueField> expectedSchemaAndValuesByColumn,
                                               SourceRecord record,
                                               String envelopeFieldName) {
        Struct content = ((Struct) record.value()).getStruct(envelopeFieldName);

        if (expectedSchemaAndValuesByColumn == null) {
            assertThat(content).isNull();
        }
        else {
            assertNotNull("expected there to be content in Envelope under " + envelopeFieldName, content);

            expectedSchemaAndValuesByColumn.forEach(
                    schemaAndValueField -> schemaAndValueField.assertFor(content)
            );
        }
    }

    protected void assertRecordOffsetAndSnapshotSource(SourceRecord record, boolean shouldBeSnapshot, boolean shouldBeLastSnapshotRecord) {
        Map<String, ?> offset = record.sourceOffset();
        assertNotNull(offset.get(SourceInfo.TXID_KEY));
        assertNotNull(offset.get(SourceInfo.TIMESTAMP_USEC_KEY));
        assertNotNull(offset.get(SourceInfo.LSN_KEY));
        Object snapshot = offset.get(SourceInfo.SNAPSHOT_KEY);

        Object lastSnapshotRecord = offset.get(SourceInfo.LAST_SNAPSHOT_RECORD_KEY);

        if (shouldBeSnapshot) {
            assertTrue("Snapshot marker expected but not found", (Boolean) snapshot);
            assertEquals("Last snapshot record marker mismatch", shouldBeLastSnapshotRecord, lastSnapshotRecord);
        }
        else {
            assertNull("Snapshot marker not expected, but found", snapshot);
            assertNull("Last snapshot marker not expected, but found", lastSnapshotRecord);
        }
        final Struct envelope = (Struct) record.value();
        if (envelope != null) {
            final Struct source = (Struct) envelope.get("source");
            final SnapshotRecord sourceSnapshot = SnapshotRecord.fromSource(source);
            if (shouldBeSnapshot) {
                if (shouldBeLastSnapshotRecord) {
                    assertEquals("Expected snapshot last record", SnapshotRecord.LAST, sourceSnapshot);
                }
                else {
                    assertEquals("Expected snapshot intermediary record", SnapshotRecord.TRUE, sourceSnapshot);
                }
            }
            else {
                assertNull("Source snapshot marker not expected, but found", sourceSnapshot);
            }
        }
    }

    protected void assertSourceInfo(SourceRecord record, String db, String schema, String table) {
        assertTrue(record.value() instanceof Struct);
        Struct source = ((Struct) record.value()).getStruct("source");
        assertEquals(db, source.getString("db"));
        assertEquals(schema, source.getString("schema"));
        assertEquals(table, source.getString("table"));
    }

    protected void assertSourceInfo(SourceRecord record) {
        assertTrue(record.value() instanceof Struct);
        Struct source = ((Struct) record.value()).getStruct("source");
        assertNotNull(source.getString("db"));
        assertNotNull(source.getString("schema"));
        assertNotNull(source.getString("table"));
    }

    protected static String topicNameFromInsertStmt(String statement) {
        String qualifiedTableNameName = tableIdFromInsertStmt(statement).toString();
        return qualifiedTableNameName.replaceAll("[ \"]", "_");
    }

    protected static TableId tableIdFromInsertStmt(String statement) {
        Matcher matcher = INSERT_TABLE_MATCHING_PATTERN.matcher(statement);
        assertTrue("Extraction of table name from insert statement failed: " + statement, matcher.matches());

        TableId id = TableId.parse(matcher.group(1), false);

        if (id.schema() == null) {
            id = new TableId(id.catalog(), "public", id.table());
        }

        return id;
    }

    protected static class SchemaAndValueField {
        private final Schema schema;
        private final Object value;
        private final String fieldName;
        private Supplier<Boolean> assertValueOnlyIf = null;

        public SchemaAndValueField(String fieldName, Schema schema, Object value) {
            this.schema = schema;
            this.value = value;
            this.fieldName = fieldName;
        }

        public SchemaAndValueField assertValueOnlyIf(final Supplier<Boolean> predicate) {
            assertValueOnlyIf = predicate;
            return this;
        }

        protected void assertFor(Struct content) {
            assertSchema(content);
            assertValue(content);
        }

        private void assertValue(Struct content) {
            if (assertValueOnlyIf != null && !assertValueOnlyIf.get()) {
                return;
            }

            if (value == null) {
                assertNull(fieldName + " is present in the actual content", content.get(fieldName));
                return;
            }
            Object actualValue = content.get(fieldName);

            // assert the value type; for List all implementation types (e.g. immutable ones) are acceptable
            if(actualValue instanceof List) {
                assertTrue("Incorrect value type for " + fieldName, value instanceof List);
                final List<?> actualValueList = (List<?>) actualValue;
                final List<?> valueList = (List<?>) value;
                assertEquals("List size don't match for " + fieldName, valueList.size(), actualValueList.size());
                if (!valueList.isEmpty() && valueList.iterator().next() instanceof Struct) {
                    for (int i = 0; i < valueList.size(); i++) {
                        assertStruct((Struct) valueList.get(i), (Struct) actualValueList.get(i));
                    }
                    return;
                }
            }
            else {
                assertEquals("Incorrect value type for " + fieldName, (value != null) ? value.getClass() : null, (actualValue != null) ? actualValue.getClass() : null);
            }

            if (actualValue instanceof byte[]) {
                assertArrayEquals("Values don't match for " + fieldName, (byte[]) value, (byte[]) actualValue);
            } else if (actualValue instanceof Struct) {
                assertStruct((Struct) value, (Struct) actualValue);
            } else {
                assertEquals("Values don't match for " + fieldName, value, actualValue);
            }
        }

        private void assertStruct(final Struct expectedStruct, final Struct actualStruct) {
            expectedStruct.schema().fields().stream().forEach(field -> {
                final Object expectedValue = expectedStruct.get(field);
                if (expectedValue == null) {
                    assertNull(fieldName + " is present in the actual content", actualStruct.get(field.name()));
                    return;
                }
                final Object actualValue = actualStruct.get(field.name());
                assertNotNull("No value found for " + fieldName, actualValue);
                assertEquals("Incorrect value type for " + fieldName, expectedValue.getClass(), actualValue.getClass());
                if (actualValue instanceof byte[]) {
                    assertArrayEquals("Values don't match for " + fieldName, (byte[]) expectedValue, (byte[]) actualValue);
                } else if (actualValue instanceof Struct) {
                    assertStruct((Struct) expectedValue, (Struct) actualValue);
                } else {
                    assertEquals("Values don't match for " + fieldName, expectedValue, actualValue);
                }
            });
        }

        private void assertSchema(Struct content) {
            if (schema == null) {
                return;
            }
            Schema schema = content.schema();
            Field field = schema.field(fieldName);
            assertNotNull(fieldName + " not found in schema " + SchemaUtil.asString(schema), field);
            VerifyRecord.assertConnectSchemasAreEqual(field.name(), field.schema(), this.schema);
        }
    }

    protected TestConsumer testConsumer(int expectedRecordsCount, String... topicPrefixes) {
         return new TestConsumer(expectedRecordsCount, topicPrefixes);
    }

    protected static class TestConsumer implements BlockingConsumer<ChangeEvent> {
        private final ConcurrentLinkedQueue<SourceRecord> records;
        private final VariableLatch latch;
        private final List<String> topicPrefixes;
        private boolean ignoreExtraRecords = false;

        protected TestConsumer(int expectedRecordsCount, String... topicPrefixes) {
            this.latch = new VariableLatch(expectedRecordsCount);
            this.records = new ConcurrentLinkedQueue<>();
            this.topicPrefixes = Arrays.stream(topicPrefixes)
                    .map(p -> TestHelper.TEST_SERVER + "." + p)
                    .collect(Collectors.toList());
        }

        public void setIgnoreExtraRecords(boolean ignoreExtraRecords) {
            this.ignoreExtraRecords = ignoreExtraRecords;
        }

        @Override
        public void accept(ChangeEvent event) {
            final SourceRecord record = event.getRecord();
            if ( ignoreTopic(record.topic()) ) {
                return;
            }

            if (latch.getCount() == 0) {
                if (ignoreExtraRecords) {
                    records.add(record);
                } else {
                    fail("received more events than expected");
                }
            } else {
                records.add(record);
                latch.countDown();
            }
        }

        private boolean ignoreTopic(String topicName) {
            if (topicPrefixes.isEmpty()) {
                return false;
            }

            for (String prefix : topicPrefixes) {
                if ( topicName.startsWith(prefix)) {
                    return false;
                }
            }

            return true;
        }

        protected void expects(int expectedRecordsCount) {
            assert latch.getCount() == 0;
            this.latch.countUp(expectedRecordsCount);
        }

        protected SourceRecord remove() {
            return records.remove();
        }

        protected boolean isEmpty() {
            return records.isEmpty();
        }

        protected void process(Consumer<SourceRecord> consumer) {
            records.forEach(consumer);
        }

        protected void clear() {
            records.clear();
        }

        protected void await(long timeout, TimeUnit unit) throws InterruptedException {
            if (!latch.await(timeout, unit)) {
                fail("Consumer is still expecting " + latch.getCount() + " records, as it received only " + records.size());
            }
        }
    }
}
