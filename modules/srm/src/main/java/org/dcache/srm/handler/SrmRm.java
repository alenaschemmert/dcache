//______________________________________________________________________________
//
// $Id$
// $Author$
//
// created 10/05 by Dmitry Litvintsev (litvinse@fnal.gov)
//
//______________________________________________________________________________

/*
 * SrmRm
 *
 * Created on 10/05
 */

package org.dcache.srm.handler;

import java.util.concurrent.CountDownLatch;

import org.dcache.srm.v2_2.TReturnStatus;
import org.dcache.srm.v2_2.TStatusCode;
import org.dcache.srm.v2_2.TSURLReturnStatus;
import org.dcache.srm.SRMUser;
import org.dcache.srm.request.RequestCredential;
import org.dcache.srm.v2_2.SrmRmRequest;
import org.dcache.srm.v2_2.SrmRmResponse;
import org.dcache.srm.AbstractStorageElement;
import org.dcache.srm.SRMException;
import org.dcache.srm.RemoveFileCallbacks;
import org.dcache.srm.v2_2.ArrayOfTSURLReturnStatus;
import org.dcache.srm.util.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.URISyntaxException;

/**
 *
 * @author  litvinse
 */

public class SrmRm {
        private static Logger logger =
            LoggerFactory.getLogger(SrmRm.class);

	AbstractStorageElement storage;
	SrmRmRequest           request;
	SrmRmResponse          response;
	SRMUser            user;
	Configuration configuration;

	public SrmRm(SRMUser user,
		     RequestCredential credential,
		     SrmRmRequest request,
		     AbstractStorageElement storage,
		     org.dcache.srm.SRM srm,
		     String client_host ) {
		this.request = request;
		this.user = user;
		this.storage = storage;
		this.configuration = srm.getConfiguration();
		if(configuration == null) {
			throw new NullPointerException("configuration is null");
		}
	}

	public SrmRmResponse getResponse() {

            if(response != null ) {
                return response;
            }

            try {
                response = srmRm();
            } catch(URISyntaxException e) {
                logger.debug(" malformed uri : "+e.getMessage());
                response = getResponse(" malformed uri : "+e.getMessage(),
                    TStatusCode.SRM_INVALID_REQUEST);
            } catch(SRMException srme) {
                logger.error(srme.toString());
                response = getFailedResponse(srme.toString());
            }
            return response;
	}

	public static final SrmRmResponse getFailedResponse(String error) {
		return getResponse(error,TStatusCode.SRM_FAILURE);
	}

	public static final  SrmRmResponse getResponse(String error,
						       TStatusCode statusCode) {
		if(statusCode == null) {
			statusCode =TStatusCode.SRM_FAILURE;
		}
		TReturnStatus status = new TReturnStatus();
		status.setStatusCode(statusCode);
		status.setExplanation(error);
		SrmRmResponse response = new SrmRmResponse();
		response.setReturnStatus(status);
		return response;
	}


    /**
     * implementation of srm rm
     */

	public SrmRmResponse srmRm()
                throws SRMException, URISyntaxException
        {
		if(request==null) {
			return getResponse(" null request passed to SrmRm()",
					   TStatusCode.SRM_INVALID_REQUEST);
		}
		if (request.getArrayOfSURLs()==null) {
			return getResponse("null array of Surls",
					   TStatusCode.SRM_INVALID_REQUEST);
		}
		org.apache.axis.types.URI[] surls =
                        request.getArrayOfSURLs().getUrlArray();
		if (surls == null || surls.length==0) {
			return getResponse("empty array of Surl Infos",
					   TStatusCode.SRM_INVALID_REQUEST);
		}
		TSURLReturnStatus[] surlReturnStatusArray =
			new TSURLReturnStatus[surls.length];
		boolean any_failed=false;
		StringBuilder error = new StringBuilder();
		RemoveFile callbacks[] = new RemoveFile[surls.length];
		for (int i = 0; i < surls.length; i++) {
			surlReturnStatusArray[i] = new TSURLReturnStatus();
			surlReturnStatusArray[i].setSurl(surls[i]);
			callbacks[i] = new RemoveFile();
		}

		int start=0;
		int end=callbacks.length>configuration.getSizeOfSingleRemoveBatch()?configuration.getSizeOfSingleRemoveBatch():callbacks.length;

		while(end<=callbacks.length) {
			for ( int i=start;i<end;i++) {
				try {
                                        URI surl = new URI(surls[i].toString());
					storage.removeFile(user,surl,callbacks[i]);
				}
				catch(RuntimeException re) {
					logger.error(re.toString());
					surlReturnStatusArray[i].setStatus(
						new TReturnStatus(
							TStatusCode.SRM_INTERNAL_ERROR,
							"RuntimeException "+re.getMessage()));
				}
				catch (Exception e) {
					logger.error(e.toString());
					surlReturnStatusArray[i].setStatus(
						new TReturnStatus(
							TStatusCode.SRM_INTERNAL_ERROR,
							"Exception "+e));

				}
			}
			try {
				for(int i = start; i<end; i++) {
					callbacks[i].waitToComplete();
					surlReturnStatusArray[i].setStatus(callbacks[i].getStatus());
					if (callbacks[i].getStatus().getStatusCode() !=
					    TStatusCode.SRM_SUCCESS) {
						any_failed=true;
						error.append(
                           surlReturnStatusArray[i].getStatus().getExplanation());
                        error.append('\n');
					}
				}
			}
			catch(InterruptedException ie) {
				throw new RuntimeException(ie);
			}
			if (end==callbacks.length) {
                            break;
                        }
			start=end;
			if (end+configuration.getSizeOfSingleRemoveBatch()<callbacks.length) {
				end+=configuration.getSizeOfSingleRemoveBatch();
			}
			else {
				end=callbacks.length;
			}
		}
		SrmRmResponse srmRmResponse;
		if ( any_failed ) {
			srmRmResponse=getFailedResponse("problem with one or more files: \n"+error);
		}
		else {
			srmRmResponse  = getResponse("successfully removed files",
						     TStatusCode.SRM_SUCCESS);
		}
		srmRmResponse.setArrayOfFileStatuses(
			new ArrayOfTSURLReturnStatus(surlReturnStatusArray));
		return srmRmResponse;
	}

	private class RemoveFile implements RemoveFileCallbacks {

		public TReturnStatus status;
            private CountDownLatch _done = new CountDownLatch(1);
		private boolean success  = true;
		public RemoveFile( ) {
		}

		public TReturnStatus getStatus() {
			return status;
		}

		public void RemoveFileFailed(String reason) {
			status = new TReturnStatus(
				TStatusCode.SRM_FAILURE,
				reason);
			logger.info("RemoveFileFailed:"+reason);
			done();
		}

		public void FileNotFound(String reason) {
			status = new TReturnStatus(
				TStatusCode.SRM_INVALID_PATH,
				reason);
			logger.info("RemoveFileFailed:"+reason);
			done();
		}

		public void RemoveFileSucceeded(){
			status = new TReturnStatus(
				TStatusCode.SRM_SUCCESS,
				null);
			done();
		}

		public void Exception(Exception e){
			status = new TReturnStatus(
				TStatusCode.SRM_FAILURE,
				"Exception: "+e.getMessage());
			TReturnStatus individualFileReturnStatus
				= new TReturnStatus();
			logger.warn(e.toString());
			done();
		}

		public void Timeout(){
			status = new TReturnStatus(
                    TStatusCode.SRM_FAILURE,
                    "Timeout: ");
			logger.warn("Timeout");
			done();
		}

            public void PermissionDenied()
            {
                status = new TReturnStatus(TStatusCode.SRM_AUTHORIZATION_FAILURE,
                                           "Permission denied");
                done();
            }

            public void waitToComplete()
                throws InterruptedException
            {
                _done.await();
            }

            public void done()
            {
                _done.countDown();
            }
	}
}
