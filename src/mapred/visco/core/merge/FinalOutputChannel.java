package visco.core.merge;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.StatusReporter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.util.ReflectionUtils;

import visco.util.ActionDelegate;
import visco.util.ModifiableBoolean;

public class FinalOutputChannel<K extends WritableComparable<K>, V extends Writable> implements IOChannel<IOChannelBuffer<K,V>> {

	/**
	 * The configuration of the job
	 */
	private JobConf jobConf;

	/**
	 * A reporter for the progress
	 */
	private Reporter reporter;

	/**
	 * The callback to execute when the merge process completes
	 */
	private final ActionDelegate onMergeCompleted;

	/**
	 * The IO channel buffer that we pass around.
	 */
	private final IOChannelBuffer<K, V> ioChannelBuffer;

	
	///////////		NEW API
	/**
	 * The output format of the job
	 */
	private org.apache.hadoop.mapreduce.OutputFormat<?,?> outputFormat;

	/**
	 * A reducer to reduce the final output data
	 */
	private org.apache.hadoop.mapreduce.Reducer<K,V,K,V> newReducer;

	/**
	 * A context to hold the reducer
	 */
	private org.apache.hadoop.mapreduce.Reducer<K,V,K,V>.Context reducerContext;
	
	/**
	 * A record writer to write the final output
	 */
	private NewTrackingRecordWriter<K,V> newTrackedRW;
		
	/**
	 * A task context to get the classes
	 */
	private TaskAttemptContext taskContext;
	
	///////////		OLD API
	
	/**
	 * A reducer to reduce the final output data
	 */
	private org.apache.hadoop.mapred.Reducer<K,V,K,V> oldReducer;
	
	/**
	 * A record writer to write the final output
	 */
	private OldTrackingRecordWriter<K,V> oldTrackedRW;
	
	/**
	 * The collector to collect the output
	 * */
	private org.apache.hadoop.mapred.OutputCollector<K, V> oldCollector;
	
	
	/**
	 * The main constructor of the class.
	 * @param jobConf
	 * 				the configuration of the job
	 * @param reporter
	 * 				a reporter for the progress
	 * @param onMergeCompleted
	 * 				the callback to execute when the merge process completes
	 */
	@SuppressWarnings("unchecked")
	public FinalOutputChannel(JobConf jobConf, Reporter reporter, Counter keyCounter, Counter valueCounter, 
			Counter outputCounter, TaskAttemptID taskId, String finalName, ActionDelegate onMergeCompleted) {
		
		this.jobConf = jobConf;
		this.ioChannelBuffer = new IOChannelBuffer<K, V>(100, this.jobConf);
		this.reporter = reporter;
		this.onMergeCompleted = onMergeCompleted;
		try {
			if(this.jobConf.getUseNewReducer()) {
				this.taskContext = new TaskAttemptContext(jobConf, taskId);
				this.outputFormat = ReflectionUtils.newInstance(taskContext.getOutputFormatClass(), this.jobConf);
				this.newReducer = (org.apache.hadoop.mapreduce.Reducer<K,V,K,V>) ReflectionUtils.newInstance(taskContext.getReducerClass(), jobConf);
				this.newTrackedRW = new NewTrackingRecordWriter<K,V>(jobConf, reporter, taskContext, outputCounter, outputFormat);
				this.reducerContext = createReduceContext(this.newReducer, taskContext.getConfiguration(), taskId, 
						keyCounter, valueCounter, this.newTrackedRW, outputFormat.getOutputCommitter(taskContext), (StatusReporter) reporter);
			} else {
				this.oldReducer = ReflectionUtils.newInstance(this.jobConf.getReducerClass(), this.jobConf);
				this.oldTrackedRW = new OldTrackingRecordWriter<K, V>((org.apache.hadoop.mapred.Counters.Counter) outputCounter, this.jobConf, reporter, finalName);
				this.oldCollector = new OutputCollector<K, V>() {
					public void collect(K key, V value) throws IOException {
						oldTrackedRW.write(key, value);
					}
				};
			}

		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}

	public IOChannelBuffer<K,V> GetEmpty(ModifiableBoolean result) {
		return ioChannelBuffer;
	}

	public void Send(IOChannelBuffer<K,V> item) {
		
		try {				
			if(this.jobConf.getUseNewReducer()) {
				while (item.size() > 0) {
					K key = item.removeKey();
					ArrayList<V> values = item.removeValues();

					this.newReducer.reduce(key, values, this.reducerContext); 
					reporter.progress();	
				}
			} else {
				while (item.size() > 0) { 
					K key = item.removeKey();
					ArrayList<V> values = item.removeValues();
					
					this.oldReducer.reduce(key, values.iterator(), this.oldCollector, this.reporter); 
					this.reporter.progress();
				}
			}
			item.clear(); // clear the buffer for the next iteration
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}


	public IOChannelBuffer<K,V> Receive(ModifiableBoolean result) {
		throw new UnsupportedOperationException("This method should never be called");
	}

	public void Release(IOChannelBuffer<K,V> item) {
		throw new UnsupportedOperationException("This method should never be called");
	}

	public void Close() {
		try {
			if(this.jobConf.getUseNewReducer()) {
				while (this.ioChannelBuffer.size() > 0) {
					K key = this.ioChannelBuffer.removeKey();
					ArrayList<V> values = this.ioChannelBuffer.removeValues();

					this.newReducer.reduce(key, values, this.reducerContext); 
					reporter.progress();
				}
				this.newTrackedRW.close(this.taskContext);
			} else {
				while (this.ioChannelBuffer.size() > 0) {
					K key = this.ioChannelBuffer.removeKey();
					ArrayList<V> values = this.ioChannelBuffer.removeValues();

					this.oldReducer.reduce(key, values.iterator(), this.oldCollector, this.reporter); 
					this.reporter.progress();	
				}
				this.oldReducer.close();
				this.oldTrackedRW.close(this.reporter);
			}
			onMergeCompleted.action();
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}

	private static final Constructor<org.apache.hadoop.mapreduce.Reducer.Context> contextConstructor;
	
	static {
		try {
			contextConstructor = 
					org.apache.hadoop.mapreduce.Reducer.Context.class.getConstructor
					(new Class[]{org.apache.hadoop.mapreduce.Reducer.class,
							Configuration.class,
							TaskAttemptID.class,
							org.apache.hadoop.mapreduce.RecordWriter.class,
							Counter.class,
							Counter.class,
							OutputCommitter.class,
							StatusReporter.class});
		} catch (NoSuchMethodException nme) {
			throw new IllegalArgumentException("Can't find constructor");
		}
	}

	@SuppressWarnings("unchecked")
	private org.apache.hadoop.mapreduce.Reducer<K,V,K,V>.Context createReduceContext(org.apache.hadoop.mapreduce.Reducer<K,V,K,V> reducer,
			Configuration job,
			TaskAttemptID taskId, 
			Counter keyCounter,
			Counter valueCounter,
			org.apache.hadoop.mapreduce.RecordWriter<K,V> output, 
			OutputCommitter committer,
			StatusReporter reporter) throws IOException, ClassNotFoundException {
		try {

			return contextConstructor.newInstance(reducer, job, taskId,
					output, keyCounter, valueCounter, committer, reporter);
		} catch (InstantiationException e) {
			throw new IOException("Can't create Context", e);
		} catch (InvocationTargetException e) {
			throw new IOException("Can't invoke Context constructor", e);
		} catch (IllegalAccessException e) {
			throw new IOException("Can't invoke Context constructor", e);
		}
	}
}
