/*
 * All GTAS code is Copyright 2016, The Department of Homeland Security (DHS), U.S. Customs and Border Protection (CBP).
 * 
 * Please see LICENSE.txt for details.
 */
package gov.gtas.services;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import gov.gtas.error.ErrorUtils;
import gov.gtas.model.Message;
import gov.gtas.model.MessageStatus;
import gov.gtas.parsers.util.FileUtils;
import gov.gtas.parsers.util.ParseUtils;
import gov.gtas.parsers.vo.MessageVo;
import gov.gtas.repository.MessageRepository;
import gov.gtas.services.search.ElasticHelper;

@Service
public class Loader {
    @Autowired
    private MessageRepository<Message> msgDao;

    @Autowired
    private ApisMessageService apisLoader;

    @Autowired
    private PnrMessageService pnrLoader;

    @Autowired
    protected ElasticHelper indexer;

    /**
     * Processes all the messages in a single file.
     * 
     * @param f
     *            the file to process
     * @return array of integers containing loaded message count at index 0 and
     *         failed message count at index 1.
     */
    public int[] processMessage(File f) {
        String filePath = f.getAbsolutePath();
        MessageDto msgDto = null;
        MessageLoaderService svc = null;
        List<String> rawMessages = null;
        try {
            if (exceedsMaxSize(f)) {
                throw new LoaderException("exceeds max file size");
            }
            msgDto = new MessageDto();
            
            byte[] raw = FileUtils.readSmallFile(filePath);
            String tmp = new String(raw, StandardCharsets.US_ASCII);
            String text = ParseUtils.stripStxEtxHeaderAndFooter(tmp);

            if (text.contains("PAXLST")) {
                svc = apisLoader;
                msgDto.setMsgType("APIS");
            } else if (text.contains("PNRGOV")) {
                svc = pnrLoader;
                msgDto.setMsgType("PNR");
            } else {
                throw new LoaderException("unrecognized file type");
            }

            msgDto.setRawMsgs(svc.preprocess(text));

        } catch (Exception e) {
            e.printStackTrace();
            String stacktrace = ErrorUtils.getStacktrace(e);
            Message m = new Message();
            m.setError(stacktrace);
            m.setFilePath(filePath);
            m.setCreateDate(new Date());
            m.setStatus(MessageStatus.FAILED_PARSING);
            msgDao.save(m);
            return null;
        }
        
/*		indexer.initClient();
		if (indexer.isDown()) {
			svc.setUseIndexer(false);
		} else {
			svc.setUseIndexer(true);
		}*/
        
        int successMsgCount = 0;
        int failedMsgCount = 0;
        msgDto.setFilepath(filePath);
        rawMessages = msgDto.getRawMsgs();
        for (String rawMessage : rawMessages) {
        	msgDto.setRawMsg(rawMessage);
            MessageDto parsedMessageDto = svc.parse(msgDto);
            if (parsedMessageDto != null && parsedMessageDto.getMsgVo() != null && svc.load(parsedMessageDto)) {
                successMsgCount++;
            } else {
                failedMsgCount++;
            }
        }
        return new int[]{successMsgCount, failedMsgCount};
    }

    private boolean exceedsMaxSize(File f) {
        final long MAX_SIZE = 4294967295l; // raw column can accommodate 4294967295 bytes 
        double numBytes = f.length();
        return numBytes > MAX_SIZE;
    }
}
