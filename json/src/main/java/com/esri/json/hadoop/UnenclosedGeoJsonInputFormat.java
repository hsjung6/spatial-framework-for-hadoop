package com.esri.json.hadoop;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.InputSplit;

// MRv2 by inheritance; MRv1 by composition/hybrid
public class UnenclosedGeoJsonInputFormat extends FileInputFormat<LongWritable, Text>
    implements org.apache.hadoop.mapred.InputFormat<LongWritable,Text> {

	// Mrv1 implementation member will be used only for getSplits().
    // Will be instantiated only when Mrv1 in use.
	private org.apache.hadoop.mapred.TextInputFormat ifmtMrv1 = null;

	@Override
	public RecordReader<LongWritable, Text> createRecordReader(InputSplit arg0, TaskAttemptContext arg1)
			throws IOException, InterruptedException {  // MRv2
		return new UnenclosedGeoJsonRecordReader();
	}

	@Override
	public org.apache.hadoop.mapred.RecordReader<LongWritable, Text> getRecordReader(  // MRv1
			org.apache.hadoop.mapred.InputSplit arg0,
			org.apache.hadoop.mapred.JobConf arg1,
			org.apache.hadoop.mapred.Reporter arg2) throws IOException {
		return new UnenclosedJsonRecordReader(arg0, arg1);
	}

	@Override
	public org.apache.hadoop.mapred.InputSplit[] getSplits(  // MRv1
			org.apache.hadoop.mapred.JobConf arg0,
			int arg1) throws IOException {
		ifmtMrv1 = (ifmtMrv1!=null) ? ifmtMrv1 :
		  new org.apache.hadoop.mapred.TextInputFormat();
		return ifmtMrv1.getSplits(arg0, arg1);
	}

}
