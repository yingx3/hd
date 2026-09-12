function main(userName, taskName, sufB, sufL, sufW, sufP)

disp('start');

global basePath outputIndex Interval

outputIndex = 1;

% 璇诲彇鍥涗釜鏁版嵁锛屽垎鍒负鐏惧墠鍦板舰zB銆佺伨鍚庡湴褰L銆佹按娣県W銆佸弬鏁癙ar
basePath = [userName, filesep, taskName, filesep];
fileNameB = [basePath, sufB, '.txt']; % 婊戝潯鍓嶆暟鎹紝鐩綍缁撴瀯涓猴細 userName\taskName\sufB.txt
fileNameL = [basePath, sufL, '.txt']; % 婊戝潯鍚庢暟鎹紝鐩綍缁撴瀯涓猴細 userName\taskName\sufL.txt
fileNameW = [basePath, sufW, '.txt']; % 姘存繁锛岀洰褰曠粨鏋勪负锛?userName\taskName\sufW.txt
fileNameP = [basePath, sufP, '.txt']; % 鍙傛暟锛岀洰褰曠粨鏋勪负锛?userName\taskName\sufP.txt

zB = load( fileNameB ); 
zL = load( fileNameL ); 
hW = load( fileNameW ); 
Par= load( fileNameP );

[Uw, Us]=Init(zB, zL, hW); parameters(Par);  k=1; T=zeros(1,k+1);
% 收敛判据：两层都接近静止并连续保持 5 步才结束，避免水层先减速时误停。
stillSteps = 0;
while max(T)<Par(8)

dt=time(Uw, Us); T(k+1)=T(k)+dt; 

[Uw, Us]=spcro(Uw, Us, dt); 

[Uw, Us]=spcrp(Uw, Us, dt);

[A, B]=velocity(Us, Uw);

process(T, Par(5));

k=k+1;

    t1 = floor(T(k-1)/Interval);
    t2 = floor(T(k)/Interval);
    if T(k)-T(k-1)>Interval
        doput(Uw, Us);
    else
        if t2 ~= t1
           doput(Uw, Us);
        end
    end

if max(max(A)) < 1 && max(max(B)) < 1
   stillSteps = stillSteps + 1;
   if stillSteps >= 5
      break;
   end
else
   stillSteps = 0;
end
    
end

disp('end');