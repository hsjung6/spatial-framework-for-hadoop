package com.esri.hadoop.hive.serde;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeStats;
import org.apache.hadoop.hive.serde2.io.ByteWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.io.ShortWritable;
import org.apache.hadoop.hive.serde2.lazy.LazyPrimitive;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StandardStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.JsonToken;

import com.esri.core.geometry.ogc.OGCGeometry;
import com.esri.hadoop.hive.GeometryUtils;
import com.esri.hadoop.shims.HiveShims;


abstract public class BaseJsonSerDe implements SerDe {
	static final Log LOG = LogFactory.getLog(BaseJsonSerDe.class.getName());

	static protected JsonFactory jsonFactory = new JsonFactory();

	protected int numColumns;
	protected int geometryColumn = -1;
	protected ArrayList<String> columnNames;
	protected ArrayList<ObjectInspector> columnOIs;
	protected boolean [] columnSet; 
	protected StructObjectInspector rowOI; // contains the type information for the fields returned
	protected String attrLabel = "attributes";  // "properties"
	
	/* rowBase keeps a base copy of the Writable for each field so they can be reused for 
	 * all records. When deserialize is called, row is initially nulled out. Then for each attribute
	 * found in the JSON record the Writable reference is copied from rowBase to row
	 * and set to the appropriate value.  Then row is returned.  This why values don't linger from 
	 * previous records.
	 */
	ArrayList<Writable> rowBase; 
	ArrayList<Writable> row;
	
	@Override
	public void initialize(Configuration cfg, Properties tbl) throws SerDeException {
				
		geometryColumn = -1;

	    // Read the configuration parameters
		String columnNameProperty = tbl.getProperty(HiveShims.serdeConstants.LIST_COLUMNS);
		String columnTypeProperty = tbl.getProperty(HiveShims.serdeConstants.LIST_COLUMN_TYPES);

		ArrayList<TypeInfo> typeInfos = TypeInfoUtils
				.getTypeInfosFromTypeString(columnTypeProperty);

		columnNames = new ArrayList<String>();
		columnNames.addAll(Arrays.asList(columnNameProperty.toLowerCase().split(",")));

		numColumns = columnNames.size();
		
		columnOIs = new ArrayList<ObjectInspector>(numColumns);
		columnSet = new boolean[numColumns];
		
		for (int c = 0; c < numColumns; c++) {

			TypeInfo colTypeInfo = typeInfos.get(c);
			
			if (colTypeInfo.getCategory() != Category.PRIMITIVE){
				throw new SerDeException("Only primitive field types are accepted");
			}
			
			if (colTypeInfo.getTypeName().equals("binary")) {

				if (geometryColumn >= 0) {
					// only one column can be defined as binary for geometries
					throw new SerDeException(
							"Multiple binary columns defined.  Define only one binary column for geometries");
				}

				columnOIs.add(GeometryUtils.geometryTransportObjectInspector);
				geometryColumn = c;
			} else {
				columnOIs.add(TypeInfoUtils.getStandardWritableObjectInspectorFromTypeInfo(colTypeInfo));
			}
		}

		// standardStruct uses ArrayList to store the row.
		rowOI = ObjectInspectorFactory.getStandardStructObjectInspector(
				columnNames, columnOIs);

		// constructing the row objects, etc, which will be reused for all rows.
		rowBase = new ArrayList<Writable>(numColumns);
		row = new ArrayList<Writable>(numColumns);
		
		// set each value in rowBase to the writable that corresponds with its PrimitiveObjectInspector
		for (int c = 0; c < numColumns; c++) {
			
			PrimitiveObjectInspector poi = (PrimitiveObjectInspector)columnOIs.get(c);
			Writable writable;
			
			try {
				writable = (Writable)poi.getPrimitiveWritableClass().newInstance();
			} catch (InstantiationException e) {
				throw new SerDeException("Error creating Writable from ObjectInspector", e);
			} catch (IllegalAccessException e) {
				throw new SerDeException("Error creating Writable from ObjectInspector", e);
			}
			
			rowBase.add(writable);
			row.add(null); // default all values to null
		}
	}  // /initialize

	@Override
	public Object deserialize(Writable json_in) throws SerDeException {
		Text json = (Text) json_in;

		// null out array because we reuse it and we don't want values persisting
		// from the last record
		for (int i=0;i<numColumns;i++)
			row.set(i, null);
		
		try {
			JsonParser parser = jsonFactory.createJsonParser(json.toString());

			JsonToken token = parser.nextToken();

			while (token != null) {

				if (token == JsonToken.START_OBJECT) {
					if ("geometry".equals(parser.getCurrentName())) {
						if (geometryColumn > -1) {
							// create geometry and insert into geometry field
							OGCGeometry ogcGeom = parseGeom(parser);
							row.set(geometryColumn, ogcGeom == null ? null :
									GeometryUtils.geometryToEsriShapeBytesWritable(ogcGeom));
						} else {
							// no geometry in select field set, don't even bother parsing
							parser.skipChildren();
						}
					} else if (attrLabel.equals(parser.getCurrentName())) {

						token = parser.nextToken();

						while (token != JsonToken.END_OBJECT && token != null) {

							// hive makes all column names in the queries column list lower case
							String name = parser.getText().toLowerCase();

							parser.nextToken();

							// figure out which column index corresponds with the attribute name
							int fieldIndex = columnNames.indexOf(name);

							if (fieldIndex >= 0) {
								setRowFieldFromParser(fieldIndex, parser);
							}

							token = parser.nextToken();
						}

						token = parser.nextToken();
					}
				}

				token = parser.nextToken();
			}

		} catch (JsonParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return row;
	}

	@Override
	public ObjectInspector getObjectInspector() throws SerDeException {
		return rowOI;
	}

	@Override
	public SerDeStats getSerDeStats() {
		return null;
	}

	@Override
	public Class<? extends Writable> getSerializedClass() {
		return Text.class;
	}

	@Override
	public Writable serialize(Object obj, ObjectInspector oi)
			throws SerDeException {

		StandardStructObjectInspector structOI = (StandardStructObjectInspector) oi;

		// get list of writables, one for each field in the row
		List<Object> fieldWritables = structOI.getStructFieldsDataAsList(obj);

		StringWriter writer = new StringWriter();

		try {
			JsonGenerator jsonGen = jsonFactory.createJsonGenerator(writer);

			jsonGen.writeStartObject();

			// first write attributes
			jsonGen.writeObjectFieldStart(attrLabel);

			for (int i = 0; i < fieldWritables.size(); i++) {
				if (i == geometryColumn)
					continue; // skip geometry, it comes later

				try {
					generateJsonFromValue(fieldWritables.get(i), i, jsonGen);
				} catch (JsonProcessingException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			jsonGen.writeEndObject();

			// if geometry column exists, write it
			if (geometryColumn > -1) {
				Object got = fieldWritables.get(geometryColumn);
				if (got == null) {
					jsonGen.writeObjectField("geometry", null);
				} else {
					BytesWritable bytesWritable = null;
					if (got instanceof BytesWritable)
						bytesWritable = (BytesWritable)got;
					else  // SparkSQL, #97
						bytesWritable = new BytesWritable((byte[])got);  // idea: avoid extra object
					OGCGeometry ogcGeometry = GeometryUtils.geometryFromEsriShape(bytesWritable);
					jsonGen.writeRaw(",\"geometry\":" + outGeom(ogcGeometry));
				}
			}

			jsonGen.writeEndObject();

			jsonGen.close();

		} catch (JsonGenerationException e) {
			LOG.error("Error generating JSON", e);
			return null;
		} catch (IOException e) {
			LOG.error("Error generating JSON", e);
			return null;
		}

		return new Text(writer.toString());
	}


	/**
	 * Send to the generator, the value of the cell, using column type
	 * 
	 * @param value The attribute value as the object given by Hive
	 * @param fieldIndex column index of field in row
	 * @param jsonGen JsonGenerator
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	private void generateJsonFromValue(Object value, int fieldIndex, JsonGenerator jsonGen)
		throws JsonProcessingException, IOException {
		String label = columnNames.get(fieldIndex);
		PrimitiveObjectInspector poi = (PrimitiveObjectInspector)this.columnOIs.get(fieldIndex);
		if (value == null) {
			jsonGen.writeObjectField(label, null);
		} else if (value instanceof LazyPrimitive<?,?>) {  // have seen LazyString, #25
			generateJsonFromLazy((LazyPrimitive<?,?>)value, fieldIndex, label, poi, jsonGen);
		} else if (value instanceof Writable) {
			generateJsonFromWritable((Writable)value, fieldIndex, label, poi, jsonGen);
		} else {  // SparkSQL, #97
			jsonGen.writeObjectField(label, value);
		}
	}
	private void generateJsonFromLazy(LazyPrimitive<?,?> value, int fieldIndex, String label,
									  PrimitiveObjectInspector poi, JsonGenerator jsonGen)
		throws JsonProcessingException, IOException {
		generateJsonFromWritable(value.getWritableObject(), fieldIndex, label, poi, jsonGen);
	}

	private void generateJsonFromWritable(Writable value, int fieldIndex, String label,
										  PrimitiveObjectInspector poi, JsonGenerator jsonGen)
		throws JsonProcessingException, IOException {
		jsonGen.writeObjectField(label, poi.getPrimitiveJavaObject(value));
	}

    // Write OGCGeometry to JSON
	abstract protected String outGeom(OGCGeometry geom);

    // Parse OGCGeometry from JSON
	abstract protected OGCGeometry parseGeom(JsonParser parser);

	/**
	 * Copies the Writable at fieldIndex from rowBase to row, then sets the value of the Writable
	 * to the value in parser
	 * 
	 * @param fieldIndex column index of field in row
	 * @param parser JsonParser pointing to the attribute
	 * @throws JsonParseException
	 * @throws IOException
	 */
	private void setRowFieldFromParser(int fieldIndex, JsonParser parser) throws JsonParseException, IOException{

		PrimitiveObjectInspector poi = (PrimitiveObjectInspector)this.columnOIs.get(fieldIndex);
		if (JsonToken.VALUE_NULL == parser.getCurrentToken())
			return;  // leave the row-cell as null

		// set the field in the row to the writable from rowBase
		row.set(fieldIndex, rowBase.get(fieldIndex));

		switch (poi.getPrimitiveCategory()){
		case BYTE:
			((ByteWritable)row.get(fieldIndex)).set(parser.getByteValue());
			break;
		case SHORT:
			((ShortWritable)row.get(fieldIndex)).set(parser.getShortValue());
			break;
		case INT:
			((IntWritable)row.get(fieldIndex)).set(parser.getIntValue());
			break;
		case LONG:
			((LongWritable)row.get(fieldIndex)).set(parser.getLongValue());
			break;
		case DOUBLE:
			((DoubleWritable)row.get(fieldIndex)).set(parser.getDoubleValue());
			break;
		case FLOAT:
			((FloatWritable)row.get(fieldIndex)).set(parser.getFloatValue());
			break;
		case BOOLEAN:
			((BooleanWritable)row.get(fieldIndex)).set(parser.getBooleanValue());
			break;
		default:  // STRING/unrecognized
			((Text)row.get(fieldIndex)).set(parser.getText());
			break;	
		}
	}

}
