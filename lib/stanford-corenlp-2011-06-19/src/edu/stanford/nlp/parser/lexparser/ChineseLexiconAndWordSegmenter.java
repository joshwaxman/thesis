package edu.stanford.nlp.parser.lexparser;

import edu.stanford.nlp.io.NumberRangeFileFilter;
import edu.stanford.nlp.io.NumberRangesFileFilter;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Sentence;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.objectbank.TokenizerFactory;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.util.Function;
import edu.stanford.nlp.process.WordSegmentingTokenizer;
import edu.stanford.nlp.process.WhitespaceTokenizer;
import edu.stanford.nlp.process.WordSegmenter;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.international.pennchinese.ChineseTreebankLanguagePack;
import edu.stanford.nlp.trees.international.pennchinese.ChineseEscaper;
import edu.stanford.nlp.util.Timing;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.text.NumberFormat;
import java.text.DecimalFormat;

/**
 * This class lets you train a lexicon and segmenter at the same time.
 *
 * @author Galen Andrew
 * @author Pi-Chuan Chang
 */
public class ChineseLexiconAndWordSegmenter implements Lexicon, WordSegmenter {

  private final ChineseLexicon chineseLexicon;
  private final WordSegmenter wordSegmenter;

  public ChineseLexiconAndWordSegmenter(ChineseLexicon lex, WordSegmenter seg) {
    chineseLexicon = lex;
    wordSegmenter = seg;
    ChineseTreebankLanguagePack.setTokenizerFactory(WordSegmentingTokenizer.factory(seg));
  }

  public ArrayList<Word> segmentWords(String s) {
    return wordSegmenter.segmentWords(s);
  }

  public boolean isKnown(int word) {
    return chineseLexicon.isKnown(word);
  }

  public boolean isKnown(String word) {
    return chineseLexicon.isKnown(word);
  }

  public Iterator<IntTaggedWord> ruleIteratorByWord(int word, int loc, String featureSpec) {
    return chineseLexicon.ruleIteratorByWord(word, loc, null);
  }

  /** Returns the number of rules (tag rewrites as word) in the Lexicon.
   *  This method assumes that the lexicon has been initialized.
   */
  public int numRules() {
    return chineseLexicon.numRules();
  }

  public void train(Collection<Tree> trees) {
    chineseLexicon.train(trees);
    wordSegmenter.train(trees);
  }

  public float score(IntTaggedWord iTW, int loc) {
    return chineseLexicon.score(iTW, loc);
  } // end score()


  public void loadSegmenter(String filename) {
    throw new UnsupportedOperationException();
  }

  public void readData(BufferedReader in) throws IOException {
    chineseLexicon.readData(in);
  }

  public void writeData(Writer w) throws IOException {
    chineseLexicon.writeData(w);
  }

  private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    ChineseTreebankLanguagePack.setTokenizerFactory(WordSegmentingTokenizer.factory(wordSegmenter));
  }

  // the data & functions below are for standalone segmenter. -pichuan
  private Options op;
  // helper function
  private static int numSubArgs(String[] args, int index) {
    int i = index;
    while (i + 1 < args.length && args[i + 1].charAt(0) != '-') {
      i++;
    }
    return i - index;
  }

  public ChineseLexiconAndWordSegmenter(Treebank trainTreebank, Options op) {
    ChineseLexiconAndWordSegmenter cs = getSegmenterDataFromTreebank(trainTreebank, op);
    chineseLexicon = cs.chineseLexicon;
    wordSegmenter = cs.wordSegmenter;
  }

  private static ChineseLexiconAndWordSegmenter getSegmenterDataFromTreebank(Treebank trainTreebank, Options op) {
    System.out.println("Currently " + new Date());
    //    printOptions(true, op);
    Timing.startTime();
    // setup tree transforms
    TreebankLangParserParams tlpParams = op.tlpParams;
    if (Test.verbose) {
      System.out.print("Training ");
      System.out.println(trainTreebank.textualSummary());
    }

    System.out.print("Binarizing trees...");
    TreeAnnotatorAndBinarizer binarizer; // initialized below
    if (!Train.leftToRight) {
      binarizer = new TreeAnnotatorAndBinarizer(tlpParams, op.forceCNF, !Train.outsideFactor(), true);
    } else {
      binarizer = new TreeAnnotatorAndBinarizer(tlpParams.headFinder(), new LeftHeadFinder(), tlpParams, op.forceCNF, !Train.outsideFactor(), true);
    }
    CollinsPuncTransformer collinsPuncTransformer = null;
    if (Train.collinsPunc) {
      collinsPuncTransformer = new CollinsPuncTransformer(tlpParams.treebankLanguagePack());
    }
    List<Tree> binaryTrainTrees = new ArrayList<Tree>();
    // List<Tree> binaryTuneTrees = new ArrayList<Tree>();

    if (Train.selectiveSplit) {
      Train.splitters = ParentAnnotationStats.getSplitCategories(trainTreebank, true, 0, Train.selectiveSplitCutOff, Train.tagSelectiveSplitCutOff, tlpParams.treebankLanguagePack());
      if (Test.verbose) {
        System.err.println("Parent split categories: " + Train.splitters);
      }
    }
    if (Train.selectivePostSplit) {
      TreeTransformer myTransformer = new TreeAnnotator(tlpParams.headFinder(), tlpParams);
      Treebank annotatedTB = trainTreebank.transform(myTransformer);
      Train.postSplitters = ParentAnnotationStats.getSplitCategories(annotatedTB, true, 0, Train.selectivePostSplitCutOff, Train.tagSelectivePostSplitCutOff, tlpParams.treebankLanguagePack());
      if (Test.verbose) {
        System.err.println("Parent post annotation split categories: " + Train.postSplitters);
      }
    }
    if (Train.hSelSplit) {
      binarizer.setDoSelectiveSplit(false);
      for (Tree tree : trainTreebank) {
        if (Train.collinsPunc) {
          tree = collinsPuncTransformer.transformTree(tree);
        }
        tree = binarizer.transformTree(tree);
      }
      binarizer.setDoSelectiveSplit(true);
    }
    for (Tree tree : trainTreebank) {
      if (Train.collinsPunc) {
        tree = collinsPuncTransformer.transformTree(tree);
      }
      tree = binarizer.transformTree(tree);
      binaryTrainTrees.add(tree);
    }

    Timing.tick("done.");
    if (Test.verbose) {
      binarizer.dumpStats();
    }
    System.out.print("Extracting Lexicon...");
    ChineseLexiconAndWordSegmenter clex = (ChineseLexiconAndWordSegmenter) op.tlpParams.lex(op.lexOptions);
    clex.train(binaryTrainTrees);
    Timing.tick("done.");
    return clex;
  }

  private static void printArgs(String[] args, PrintStream ps) {
    ps.print("ChineseLexiconAndWordSegmenter invoked with arguments:");
    for (int i = 0; i < args.length; i++) {
      ps.print(" " + args[i]);
    }
    ps.println();
  }

  static void saveSegmenterDataToSerialized(ChineseLexiconAndWordSegmenter cs, String filename) {
    try {
      System.err.print("Writing segmenter in serialized format to file " + filename + " ");
      ObjectOutputStream out = IOUtils.writeStreamFromString(filename);

      out.writeObject(cs);
      out.close();
      System.err.println("done.");
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }
  }


  static void saveSegmenterDataToText(ChineseLexiconAndWordSegmenter cs, String filename) {
    try {
      System.err.print("Writing parser in text grammar format to file " + filename);
      OutputStream os;
      if (filename.endsWith(".gz")) {
        // it's faster to do the buffering _outside_ the gzipping as here
        os = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(filename)));
      } else {
        os = new BufferedOutputStream(new FileOutputStream(filename));
      }
      PrintWriter out = new PrintWriter(os);
      String prefix = "BEGIN ";
      //      out.println(prefix + "OPTIONS");
      //      if (pd.pt != null) {
      //        pd.pt.writeData(out);
      //      }
      //      out.println();
      //      System.err.print(".");
      out.println(prefix + "LEXICON");
      if (cs != null) {
        cs.writeData(out);
      }
      out.println();
      System.err.print(".");
      out.flush();
      out.close();
      System.err.println("done.");
    } catch (IOException e) {
      System.err.println("Trouble saving segmenter data to ASCII format.");
      e.printStackTrace();
    }
  }

  private static Treebank makeTreebank(String treebankPath, Options op, FileFilter filt) {
    System.err.println("Training a segmenter from treebank dir: " + treebankPath);
    Treebank trainTreebank = op.tlpParams.memoryTreebank();
    System.err.print("Reading trees...");
    if (filt == null) {
      trainTreebank.loadPath(treebankPath);
    } else {
      trainTreebank.loadPath(treebankPath, filt);
    }

    Timing.tick("done [read " + trainTreebank.size() + " trees].");
    return trainTreebank;
  }

  /**
   * Construct a new ChineseLexiconAndWordSegmenter.  This loads a segmenter file that
   * was previously assembled and stored.
   *
   * @throws IllegalArgumentException If segmenter data cannot be loaded
   */
  public ChineseLexiconAndWordSegmenter(String segmenterFileOrUrl, Options op) {
    ChineseLexiconAndWordSegmenter cs = getSegmenterDataFromFile(segmenterFileOrUrl, op);
    this.op = cs.op; // in case a serialized options was read in
    chineseLexicon = cs.chineseLexicon;
    wordSegmenter = cs.wordSegmenter;
  }

  public static ChineseLexiconAndWordSegmenter getSegmenterDataFromFile(String parserFileOrUrl, Options op) {
    ChineseLexiconAndWordSegmenter cs = getSegmenterDataFromSerializedFile(parserFileOrUrl);
    if (cs == null) {
//      pd = getSegmenterDataFromTextFile(parserFileOrUrl, op);
    }
    return cs;
  }

  protected static ChineseLexiconAndWordSegmenter getSegmenterDataFromSerializedFile(String serializedFileOrUrl) {
    ChineseLexiconAndWordSegmenter cs = null;
    try {
      System.err.print("Loading segmenter from serialized file " + serializedFileOrUrl + " ...");
      ObjectInputStream in;
      InputStream is;
      if (serializedFileOrUrl.startsWith("http://")) {
        URL u = new URL(serializedFileOrUrl);
        URLConnection uc = u.openConnection();
        is = uc.getInputStream();
      } else {
        is = new FileInputStream(serializedFileOrUrl);
      }
      if (serializedFileOrUrl.endsWith(".gz")) {
        // it's faster to do the buffering _outside_ the gzipping as here
        in = new ObjectInputStream(new BufferedInputStream(new GZIPInputStream(is)));
      } else {
        in = new ObjectInputStream(new BufferedInputStream(is));
      }
      cs = (ChineseLexiconAndWordSegmenter) in.readObject();
      in.close();
      System.err.println(" done.");
      return cs;
    } catch (InvalidClassException ice) {
      // For this, it's not a good idea to continue and try it as a text file!
      System.err.println();   // as in middle of line from above message
      throw new RuntimeException(ice);
    } catch (FileNotFoundException fnfe) {
      // For this, it's not a good idea to continue and try it as a text file!
      System.err.println();   // as in middle of line from above message
      throw new RuntimeException(fnfe);
    } catch (StreamCorruptedException sce) {
      // suppress error message, on the assumption that we've really got
      // a text grammar, and that'll be tried next
    } catch (Exception e) {
      System.err.println();   // as in middle of line from above message
      e.printStackTrace();
    }
    return null;
  }

  /** This method lets you train and test a segmenter relative to a
   *  Treebank.
   *  <p>
   *  <i>Implementation note:</i> This method is largely cloned from
   *  LexicalizedParser's main method.  Should we try to have it be able
   *  to train segmenters to stop things going out of sync?
   */
  public static void main(String[] args) {
    boolean train = false;
    boolean saveToSerializedFile = false;
    boolean saveToTextFile = false;
    String serializedInputFileOrUrl = null;
    String textInputFileOrUrl = null;
    String serializedOutputFileOrUrl = null;
    String textOutputFileOrUrl = null;
    String treebankPath = null;
    Treebank testTreebank = null;
    Treebank tuneTreebank = null;
    String testPath = null;
    FileFilter testFilter = null;
    FileFilter trainFilter = null;
    String encoding = null;

    // variables needed to process the files to be parsed
    TokenizerFactory<Word> tokenizerFactory = null;
//    DocumentPreprocessor documentPreprocessor = new DocumentPreprocessor();
    boolean tokenized = false; // whether or not the input file has already been tokenized
    Function<List<HasWord>, List<HasWord>> escaper = new ChineseEscaper();
    int tagDelimiter = -1;
    String sentenceDelimiter = "\n";
    boolean fromXML = false;
    int argIndex = 0;
    if (args.length < 1) {
      System.err.println("usage: java edu.stanford.nlp.parser.lexparser." + 
                         "LexicalizedParser parserFileOrUrl filename*");
      System.exit(1);
    }

    Options op = new Options();
    op.tlpParams = new ChineseTreebankParserParams();

    // while loop through option arguments
    while (argIndex < args.length && args[argIndex].charAt(0) == '-') {
      if (args[argIndex].equalsIgnoreCase("-train")) {
        train = true;
        saveToSerializedFile = true;
        int numSubArgs = numSubArgs(args, argIndex);
        argIndex++;
        if (numSubArgs > 1) {
          treebankPath = args[argIndex];
          argIndex++;
        } else {
          throw new RuntimeException("Error: -train option must have treebankPath as first argument.");
        }
        if (numSubArgs == 2) {
          trainFilter = new NumberRangesFileFilter(args[argIndex++], true);
        } else if (numSubArgs >= 3) {
          try {
            int low = Integer.parseInt(args[argIndex]);
            int high = Integer.parseInt(args[argIndex + 1]);
            trainFilter = new NumberRangeFileFilter(low, high, true);
            argIndex += 2;
          } catch (NumberFormatException e) {
            // maybe it's a ranges expression?
            trainFilter = new NumberRangesFileFilter(args[argIndex], true);
            argIndex++;
          }
        }
      } else if (args[argIndex].equalsIgnoreCase("-encoding")) { // sets encoding for TreebankLangParserParams
        encoding = args[argIndex + 1];
        op.tlpParams.setInputEncoding(encoding);
        op.tlpParams.setOutputEncoding(encoding);
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-loadFromSerializedFile")) {
        // load the parser from a binary serialized file
        // the next argument must be the path to the parser file
        serializedInputFileOrUrl = args[argIndex + 1];
        argIndex += 2;
        // doesn't make sense to load from TextFile -pichuan
        //      } else if (args[argIndex].equalsIgnoreCase("-loadFromTextFile")) {
        //        // load the parser from declarative text file
        //        // the next argument must be the path to the parser file
        //        textInputFileOrUrl = args[argIndex + 1];
        //        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-saveToSerializedFile")) {
        saveToSerializedFile = true;
        serializedOutputFileOrUrl = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-saveToTextFile")) {
        // save the parser to declarative text file
        saveToTextFile = true;
        textOutputFileOrUrl = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-treebank")) {
        // the next argument is the treebank path and range for testing
        int numSubArgs = numSubArgs(args, argIndex);
        argIndex++;
        if (numSubArgs == 1) {
          testFilter = new NumberRangesFileFilter(args[argIndex++], true);
        } else if (numSubArgs > 1) {
          testPath = args[argIndex++];
          if (numSubArgs == 2) {
            testFilter = new NumberRangesFileFilter(args[argIndex++], true);
          } else if (numSubArgs >= 3) {
            try {
              int low = Integer.parseInt(args[argIndex]);
              int high = Integer.parseInt(args[argIndex + 1]);
              testFilter = new NumberRangeFileFilter(low, high, true);
              argIndex += 2;
            } catch (NumberFormatException e) {
              // maybe it's a ranges expression?
              testFilter = new NumberRangesFileFilter(args[argIndex++], true);
            }
          }
        }
      } else {
        int j = op.tlpParams.setOptionFlag(args, argIndex);
        if (j == argIndex) {
          System.err.println("Unknown option ignored: " + args[argIndex]);
          j++;
        }
        argIndex = j;
      }
    } // end while loop through arguments

    TreebankLangParserParams tlpParams = op.tlpParams;

    // all other arguments are order dependent and
    // are processed in order below

    ChineseLexiconAndWordSegmenter cs = null;
    if (!train && Test.verbose) {
      System.out.println("Currently " + new Date());
      printArgs(args, System.out);
    }
    if (train) {
      printArgs(args, System.out);
      // so we train a parser using the treebank
      if (treebankPath == null) {
        // the next arg must be the treebank path, since it wasn't give earlier
        treebankPath = args[argIndex];
        argIndex++;
        if (args.length > argIndex + 1) {
          try {
            // the next two args might be the range
            int low = Integer.parseInt(args[argIndex]);
            int high = Integer.parseInt(args[argIndex + 1]);
            trainFilter = new NumberRangeFileFilter(low, high, true);
            argIndex += 2;
          } catch (NumberFormatException e) {
            // maybe it's a ranges expression?
            trainFilter = new NumberRangesFileFilter(args[argIndex], true);
            argIndex++;
          }
        }
      }
      Treebank trainTreebank = makeTreebank(treebankPath, op, trainFilter);
      cs = new ChineseLexiconAndWordSegmenter(trainTreebank, op);
    } else if (textInputFileOrUrl != null) {
      // so we load the segmenter from a text grammar file
      // XXXXX fix later -pichuan
      //cs = new LexicalizedParser(textInputFileOrUrl, true, op);
    } else {
      // so we load a serialized segmenter
      if (serializedInputFileOrUrl == null) {
        // the next argument must be the path to the serialized parser
        serializedInputFileOrUrl = args[argIndex];
        argIndex++;
      }
      try {
        cs = new ChineseLexiconAndWordSegmenter(serializedInputFileOrUrl, op);
      } catch (IllegalArgumentException e) {
        System.err.println("Error loading segmenter, exiting...");
        System.exit(0);
      }
    }

    // the following has to go after reading parser to make sure
    // op and tlpParams are the same for train and test
    TreePrint treePrint = Test.treePrint(tlpParams);

    if (testFilter != null) {
      if (testPath == null) {
        if (treebankPath == null) {
          throw new RuntimeException("No test treebank path specified...");
        } else {
          System.err.println("No test treebank path specified.  Using train path: \"" + treebankPath + "\"");
          testPath = treebankPath;
        }
      }
      testTreebank = tlpParams.testMemoryTreebank();
      testTreebank.loadPath(testPath, testFilter);
    }

    Train.sisterSplitters = new HashSet<String>(Arrays.asList(tlpParams.sisterSplitters()));

    // at this point we should be sure that op.tlpParams is
    // set appropriately (from command line, or from grammar file),
    // and will never change again.  We also set the tlpParams of the
    // LexicalizedParser instance to be the same object.  This is
    // redundancy that we probably should take out eventually.
    //
    // -- Roger
    if (Test.verbose) {
      System.err.println("Lexicon is " + cs.getClass().getName());
    }

    PrintWriter pwOut = tlpParams.pw();
    PrintWriter pwErr = tlpParams.pw(System.err);


    // Now what do we do with the parser we've made
    if (saveToTextFile) {
      // save the parser to textGrammar format
      if (textOutputFileOrUrl != null) {
        saveSegmenterDataToText(cs, textOutputFileOrUrl);
      } else {
        System.err.println("Usage: must specify a text segmenter data output path");
      }
    }
    if (saveToSerializedFile) {
      if (serializedOutputFileOrUrl == null && argIndex < args.length) {
        // the next argument must be the path to serialize to
        serializedOutputFileOrUrl = args[argIndex];
        argIndex++;
      }
      if (serializedOutputFileOrUrl != null) {
        saveSegmenterDataToSerialized(cs, serializedOutputFileOrUrl);
      } else if (textOutputFileOrUrl == null && testTreebank == null) {
        // no saving/parsing request has been specified
        System.err.println("usage: " + "java edu.stanford.nlp.parser.lexparser.ChineseLexiconAndWordSegmenter" + "-train trainFilesPath [start stop] serializedParserFilename");
      }
    }
    /* --------------------- Testing part!!!! ----------------------- */
    if (Test.verbose) {
//      printOptions(false, op);
    }
    if (testTreebank != null || (argIndex < args.length && args[argIndex].equalsIgnoreCase("-treebank"))) {
      // test parser on treebank
      if (testTreebank == null) {
        // the next argument is the treebank path and range for testing
        testTreebank = tlpParams.testMemoryTreebank();
        if (args.length < argIndex + 4) {
          testTreebank.loadPath(args[argIndex + 1]);
        } else {
          int testlow = Integer.parseInt(args[argIndex + 2]);
          int testhigh = Integer.parseInt(args[argIndex + 3]);
          testTreebank.loadPath(args[argIndex + 1], new NumberRangeFileFilter(testlow, testhigh, true));
        }
      }
      /* TODO - test segmenting on treebank. -pichuan */
//      lp.testOnTreebank(testTreebank);
//    } else if (argIndex >= args.length) {
//      // no more arguments, so we just parse our own test sentence
//      if (lp.parse(op.tlpParams.defaultTestSentence())) {
//        treePrint.printTree(lp.getBestParse(), pwOut);
//      } else {
//        pwErr.println("Error. Can't parse test sentence: " +
//              lp.parse(op.tlpParams.defaultTestSentence()));
//      }
    }
//wsg2010: This code block doesn't actually do anything. It appears to read and tokenize a file, and then just print it.
//         There are easier ways to do that. This code was copied from an old version of LexicalizedParser.
//    else {
//      // We parse filenames given by the remaining arguments
//      int numWords = 0;
//      Timing timer = new Timing();
//      // set the tokenizer
//      if (tokenized) {
//        tokenizerFactory = WhitespaceTokenizer.factory();
//      }
//      TreebankLanguagePack tlp = tlpParams.treebankLanguagePack();
//      if (tokenizerFactory == null) {
//        tokenizerFactory = (TokenizerFactory<Word>) tlp.getTokenizerFactory();
//      }
//      documentPreprocessor.setTokenizerFactory(tokenizerFactory);
//      documentPreprocessor.setSentenceFinalPuncWords(tlp.sentenceFinalPunctuationWords());
//      if (encoding != null) {
//        documentPreprocessor.setEncoding(encoding);
//      }
//      timer.start();
//      for (int i = argIndex; i < args.length; i++) {
//        String filename = args[i];
//        try {
//          List document = null;
//          if (fromXML) {
//            document = documentPreprocessor.getSentencesFromXML(filename, sentenceDelimiter, tokenized);
//          } else {
//            document = documentPreprocessor.getSentencesFromText(filename, escaper, sentenceDelimiter, tagDelimiter);
//          }
//          System.err.println("Segmenting file: " + filename + " with " + document.size() + " sentences.");
//          PrintWriter pwo = pwOut;
//          if (Test.writeOutputFiles) {
//            try {
//              pwo = tlpParams.pw(new FileOutputStream(filename + ".stp"));
//            } catch (IOException ioe) {
//              ioe.printStackTrace();
//            }
//          }
//          int num = 0;
//          treePrint.printHeader(pwo, tlp.getEncoding());
//          for (Iterator it = document.iterator(); it.hasNext();) {
//            num++;
//            List sentence = (List) it.next();
//            int len = sentence.size();
//            numWords += len;
////            pwErr.println("Parsing [sent. " + num + " len. " + len + "]: " + sentence);
//            pwo.println(Sentence.listToString(sentence));
//          }
//          treePrint.printFooter(pwo);
//          if (Test.writeOutputFiles) {
//            pwo.close();
//          }
//        } catch (IOException e) {
//          pwErr.println("Couldn't find file: " + filename);
//        }
//
//      } // end for each file
//      long millis = timer.stop();
//      double wordspersec = numWords / (((double) millis) / 1000);
//      NumberFormat nf = new DecimalFormat("0.00"); // easier way!
//      pwErr.println("Segmented " + numWords + " words at " + nf.format(wordspersec) + " words per second.");
//    }
  }

  private static final long serialVersionUID = -6554995189795187918L;


  public UnknownWordModel getUnknownWordModel() {
    return chineseLexicon.getUnknownWordModel();
  }

  public void setUnknownWordModel(UnknownWordModel uwm) {
    chineseLexicon.setUnknownWordModel(uwm);
  }

}
